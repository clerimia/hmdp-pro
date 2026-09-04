package com.hmdp.mq;

import com.hmdp.observability.MqTraceCarrier;
import com.hmdp.observability.SeckillMetrics;
import com.hmdp.observability.TraceContext;
import com.hmdp.utils.RocketMQConstants;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;

/**
 * 死信监控消费者（P2）：订单消息超过重试上限（{@code OrderMQConsumer#MAX_RECONSUME_TIMES}）后，
 * Broker 自动把消息转入 {@code %DLQ%order-consumer-group}。
 *
 * <p>死信 = 重试链路已放弃，消息不丢单但不保证及时处理——CREATE 死信等对账补单
 * （SeckillReconcileTask#supplementMissingOrders），TIMEOUT 死信等关单兜底
 * （closeTimeoutOrders）。这里只做监控告警打点 + 留痕日志，不尝试重新处理：
 * 在死信消费者里重投等于把重试上限变成摆设，拖尾又回来了。
 *
 * <p>注意 topic 在首条死信产生时才由 Broker 创建，启动时订阅不存在的 topic 是正常的
 * （无队列分配、静默等待），不是错误。
 */
@Slf4j
@Component
public class OrderDeadLetterConsumer {

    /** 死信 topic 固定格式：%DLQ% + 消费者组名 */
    private static final String DLQ_TOPIC = "%DLQ%" + RocketMQConstants.ORDER_CONSUMER_GROUP;

    /** 必须用独立消费者组：复用原组订阅不同 topic 会打乱原组的订阅关系与位点 */
    private static final String DLQ_CONSUMER_GROUP = "order-dlq-monitor-group";

    @Value("${rocketmq.name-server}")
    private String nameServer;

    private final SeckillMetrics seckillMetrics;

    public OrderDeadLetterConsumer(SeckillMetrics seckillMetrics) {
        this.seckillMetrics = seckillMetrics;
    }

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() throws Exception {
        consumer = new DefaultMQPushConsumer(DLQ_CONSUMER_GROUP);
        consumer.setNamesrvAddr(nameServer);
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(DLQ_TOPIC, "*");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                TraceContext.open(MqTraceCarrier.extract(msg));
                try {
                    seckillMetrics.orderDeadLetter();
                    String body = msg.getBody() == null ? "" : new String(msg.getBody(), StandardCharsets.UTF_8);
                    // 原始 topic 存在 properties 里（重试消息的 RETRY_TOPIC / 死信的 REAL_TOPIC）
                    log.error("死信告警：订单消息重试上限已耗尽，等待对账兜底/人工介入, "
                                    + "originTopic={}, msgId={}, reconsumeTimes={}, body={}",
                            msg.getProperty("RETRY_TOPIC"), msg.getMsgId(),
                            msg.getReconsumeTimes(), body);
                } finally {
                    TraceContext.clear();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ 死信监控消费者启动成功，订阅 {}", DLQ_TOPIC);
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
