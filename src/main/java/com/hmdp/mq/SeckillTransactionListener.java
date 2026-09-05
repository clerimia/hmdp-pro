package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.observability.MqTraceCarrier;
import com.hmdp.observability.TraceContext;
import com.hmdp.service.IVoucherOrderService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.LocalTransactionState;
import org.apache.rocketmq.client.producer.TransactionListener;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * 秒杀事务消息监听：半消息落盘后执行 Lua；异常未上报时由 Broker 回查事务标记
 */
@Slf4j
@Component
public class SeckillTransactionListener implements TransactionListener {

    @Resource
    private IVoucherOrderService voucherOrderService;

    /**
     * 熔断器手动引用：回查回调由 RocketMQ producer 内部线程直接调用，
     * 不经过 Spring 代理，@CircuitBreaker 注解在这里不会生效——只能编程式包裹
     * （与 ShopServiceImpl 的 dbFallbackBulkhead 同一个坑）。
     * 注意用的是 redisBreaker：回查查的是 seckill:txn 标记（Redis 依赖），
     * 按「按依赖拆分爆炸半径」原则应归入 Redis 熔断器。
     */
    @Resource
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        SeckillTxContext ctx = (SeckillTxContext) arg;
        VoucherOrder order = ctx.getOrder();
        try {
            long result = voucherOrderService.executeSeckillLocalTransaction(
                    order.getVoucherId(), order.getUserId(), order.getId());
            ctx.setLuaResult(result);
            if (result == 0) {
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            // 库存不足 / 重复：半消息丢弃，不投递
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception e) {
            log.error("秒杀本地事务执行异常, orderId={}", order.getId(), e);
            ctx.setLuaResult(-1);
            // 未知：交给 Broker 回查；若 Lua 未写标记则最终 ROLLBACK
            return LocalTransactionState.UNKNOW;
        }
    }

    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        // 事务回查由 Broker 发起、跑在生产者内部的回查线程上，与发送线程不是同一个线程，
        // 只能从消息 properties 还原 traceId（这里没有提交方上下文可继承）
        TraceContext.open(MqTraceCarrier.extract(msg));
        try {
            VoucherOrder order = JSONUtil.toBean(
                    new String(msg.getBody(), StandardCharsets.UTF_8), VoucherOrder.class);
            if (order.getId() == null) {
                log.warn("事务回查消息体缺少 orderId，回滚");
                return LocalTransactionState.ROLLBACK_MESSAGE;
            }
            // redisBreaker 包裹标记查询：该查询是 Redis 依赖，计入 redisBreaker 才符合
            // 按依赖归因原则（此前误用 dbBreaker——Redis 故障会打开「DB 熔断器」，
            // 传导到入口 dbDegraded() 让新订单误报 ORDER_PROCESSING）。
            // 熔断打开时 executeSupplier 直接抛 CallNotPermittedException，
            // 走下面的 catch 返回 UNKNOW 等待下次回查，不在故障期反复打 Redis
            boolean exists = circuitBreakerRegistry.circuitBreaker("redisBreaker")
                    .executeSupplier(() -> voucherOrderService.hasSeckillTxnMarker(order.getId()));
            if (exists) {
                log.info("事务回查：标记存在，COMMIT, orderId={}", order.getId());
                return LocalTransactionState.COMMIT_MESSAGE;
            }
            log.info("事务回查：标记不存在，ROLLBACK, orderId={}", order.getId());
            return LocalTransactionState.ROLLBACK_MESSAGE;
        } catch (Exception e) {
            log.error("事务回查异常，返回 UNKNOW 等待下次回查", e);
            return LocalTransactionState.UNKNOW;
        } finally {
            TraceContext.clear();
        }
    }
}
