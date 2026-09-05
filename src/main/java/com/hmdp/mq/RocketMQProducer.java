package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.ErrorCode;
import com.hmdp.exception.SystemException;
import com.hmdp.observability.MqTraceCarrier;
import com.hmdp.utils.RocketMQConstants;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.TransactionMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * RocketMQ 生产者：秒杀订单事务消息（CREATE）
 */
@Slf4j
@Component
public class RocketMQProducer {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Resource
    private SeckillTransactionListener seckillTransactionListener;

    @Resource
    private com.hmdp.observability.ResilienceMetrics resilienceMetrics;

    @Resource
    private com.hmdp.observability.SeckillMetrics seckillMetrics;

    private TransactionMQProducer producer;

    @PostConstruct
    public void init() throws MQClientException {
        producer = new TransactionMQProducer(RocketMQConstants.ORDER_TX_PRODUCER_GROUP);
        producer.setNamesrvAddr(nameServer);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setSendMsgTimeout(3000);
        producer.setTransactionListener(seckillTransactionListener);
        // 回查回调线程池（Broker 发起 checkLocalTransaction）
        producer.setExecutorService(new ThreadPoolExecutor(
                2, 5, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(200),
                new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "seckill-tx-check-" + idx.incrementAndGet());
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy()));
        producer.start();
        log.info("RocketMQ 事务生产者启动成功，nameserver={}", nameServer);
    }

    /**
     * 发送订单创建事务消息：先半消息，再在 TransactionListener 中执行 Lua，成功则 COMMIT（方案 A）
     *
     * <p>P2 容错：{@code mqBreaker} 统计发送失败率（50%/窗口 20/半开 10s）。熔断打开后新请求
     * 直接被 fallback 语义化拒绝——半消息不再发、本地事务（Lua）不会执行，Redis/DB 一个都不碰，
     * 这就是「MQ 挂时秒杀快速失败，不打 DB」。
     *
     * <p><b>不叠 R4J Retry</b>：client 已有 {@code retryTimesWhenSendFailed=2} 次重试，
     * 后面还有事务回查 + 对账补单两层兜底，应用层再重试只会放大故障期无效流量（重试风暴）。
     */
    @CircuitBreaker(name = "mqBreaker", fallbackMethod = "sendOrderCreateInTransactionFallback")
    public SendResult sendOrderCreateInTransaction(VoucherOrder order, SeckillTxContext ctx)
            throws MQClientException {
        Message msg = new Message(
                RocketMQConstants.ORDER_TOPIC,
                RocketMQConstants.ORDER_TAG_CREATE,
                JSONUtil.toJsonStr(order).getBytes(StandardCharsets.UTF_8));
        // 跨进程透传：traceId 随消息 properties 走到消费者（放 body 会污染业务契约）
        MqTraceCarrier.inject(msg);
        SendResult result = producer.sendMessageInTransaction(msg, ctx);
        log.debug("订单事务消息发送结束, orderId={}, sendStatus={}, luaResult={}",
                order.getId(), result.getSendStatus(), ctx.getLuaResult());
        return result;
    }

    /**
     * mqBreaker 降级：熔断打开（CallNotPermittedException）时语义化拒绝；
     * 学习期的真实发送失败原样上抛，交回调用方现有处理路径（fail + 打点）。
     */
    private SendResult sendOrderCreateInTransactionFallback(VoucherOrder order, SeckillTxContext ctx,
                                                            Throwable t) throws Exception {
        if (t instanceof CallNotPermittedException) {
            log.warn("mqBreaker 熔断打开，事务消息快速失败, orderId={}", order.getId());
            // 降级可见（P3）：fallback 打点 + 秒杀 reason 体系里的 mq_send_error 降级量
            resilienceMetrics.fallback(
                    "mqBreaker", com.hmdp.observability.ResilienceMetrics.KIND_NOT_PERMITTED);
            seckillMetrics.degraded("mqBreaker", com.hmdp.observability.SeckillMetrics.Reason.MQ_SEND_ERROR);
            throw new SystemException(ErrorCode.SYS_MQ_UNAVAILABLE, ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
        }
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        throw new IllegalStateException(t);
    }

    /**
     * 发送订单创建普通消息（对账补单用）。熔断语义同 {@link #sendOrderCreateInTransaction}。
     */
    @CircuitBreaker(name = "mqBreaker", fallbackMethod = "sendOrderCreateFallback")
    public SendResult sendOrderCreate(VoucherOrder order)
            throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        Message msg = new Message(
                RocketMQConstants.ORDER_TOPIC,
                RocketMQConstants.ORDER_TAG_CREATE,
                JSONUtil.toJsonStr(order).getBytes(StandardCharsets.UTF_8));
        MqTraceCarrier.inject(msg);
        SendResult result = producer.send(msg);
        log.debug("订单创建消息发送成功(方案B), orderId={}, msgId={}", order.getId(), result.getMsgId());
        return result;
    }

    /** mqBreaker 降级（普通消息版），行为与事务消息 fallback 一致 */
    private SendResult sendOrderCreateFallback(VoucherOrder order, Throwable t) throws Exception {
        if (t instanceof CallNotPermittedException) {
            log.warn("mqBreaker 熔断打开，普通消息快速失败, orderId={}", order.getId());
            resilienceMetrics.fallback(
                    "mqBreaker", com.hmdp.observability.ResilienceMetrics.KIND_NOT_PERMITTED);
            seckillMetrics.degraded("mqBreaker", com.hmdp.observability.SeckillMetrics.Reason.MQ_SEND_ERROR);
            throw new SystemException(ErrorCode.SYS_MQ_UNAVAILABLE, ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
        }
        if (t instanceof Exception) {
            throw (Exception) t;
        }
        throw new IllegalStateException(t);
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}
