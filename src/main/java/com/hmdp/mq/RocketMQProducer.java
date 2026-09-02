package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.observability.MqTraceCarrier;
import com.hmdp.utils.RocketMQConstants;
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
 * RocketMQ 生产者：秒杀订单事务消息（CREATE）+ 超时关单延迟消息（TIMEOUT）
 */
@Slf4j
@Component
public class RocketMQProducer {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    @Resource
    private SeckillTransactionListener seckillTransactionListener;

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
     */
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
     * 发送订单创建普通消息（方案 B：入口已限流并写入 WAITING，库存校验在消费者）
     */
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

    /**
     * 发送超时关单延迟消息（broker 按档位延迟投递，到点触发关单）
     */
    public void sendOrderTimeout(Long orderId)
            throws MQClientException, MQBrokerException, RemotingException, InterruptedException {
        Message msg = new Message(
                RocketMQConstants.ORDER_TOPIC,
                RocketMQConstants.ORDER_TAG_TIMEOUT,
                orderId.toString().getBytes(StandardCharsets.UTF_8));
        msg.setDelayTimeLevel(RocketMQConstants.ORDER_TIMEOUT_DELAY_LEVEL);
        MqTraceCarrier.inject(msg);
        producer.send(msg);
        log.debug("超时关单延迟消息发送成功, orderId={}, delayLevel={}",
                orderId, RocketMQConstants.ORDER_TIMEOUT_DELAY_LEVEL);
    }

    @PreDestroy
    public void destroy() {
        if (producer != null) {
            producer.shutdown();
        }
    }
}
