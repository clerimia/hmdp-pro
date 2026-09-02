package com.hmdp.mq;

import cn.hutool.json.JSONUtil;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.observability.MqTraceCarrier;
import com.hmdp.observability.SeckillMetrics;
import com.hmdp.observability.TraceContext;
import com.hmdp.service.IVoucherOrderService;
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
 * 秒杀订单消费者：消费 CREATE（异步落库）与 TIMEOUT（超时关单）消息
 * 消费失败返回 RECONSUME_LATER，由 RocketMQ 按重试间隔自动重投，配合业务幂等保证不丢单
 */
@Slf4j
@Component
public class OrderMQConsumer {

    @Value("${rocketmq.name-server}")
    private String nameServer;

    private final IVoucherOrderService voucherOrderService;

    private final SeckillMetrics seckillMetrics;

    public OrderMQConsumer(IVoucherOrderService voucherOrderService, SeckillMetrics seckillMetrics) {
        this.voucherOrderService = voucherOrderService;
        this.seckillMetrics = seckillMetrics;
    }

    private DefaultMQPushConsumer consumer;

    @PostConstruct
    public void init() throws Exception {
        consumer = new DefaultMQPushConsumer(RocketMQConstants.ORDER_CONSUMER_GROUP);
        consumer.setNamesrvAddr(nameServer);
        // 消费者组首次启动时从最早位置消费；之后从上次消费位点续读
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(RocketMQConstants.ORDER_TOPIC, "CREATE || TIMEOUT");
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            for (MessageExt msg : msgs) {
                String tag = msg.getTags();
                // 消费线程来自 RocketMQ 内部线程池，发消息时的 MDC 到这儿已经断掉，
                // 必须显式从消息 properties 恢复（重试消息会带 -r{n} 后缀）
                TraceContext.open(MqTraceCarrier.extract(msg));
                try {
                    boolean consumed = handleMessage(msg, tag);
                    seckillMetrics.orderConsumed(tag, consumed);
                    if (!consumed) {
                        // 消费失败稍后重试；业务处理本身幂等，重复消费安全
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                } finally {
                    // 消费线程同样是池化复用的，不清理会让下一条消息继承上一条的 traceId
                    TraceContext.clear();
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ 消费者启动成功，订阅 {}/CREATE || TIMEOUT", RocketMQConstants.ORDER_TOPIC);
    }

    /**
     * 处理单条消息。
     *
     * @return true 消费成功；false 需要重试（RocketMQ 并发消费下返回 RECONSUME_LATER 会让本批消息整体重投，
     * 依赖业务幂等兜底）
     */
    private boolean handleMessage(MessageExt msg, String tag) {
        String body = new String(msg.getBody(), StandardCharsets.UTF_8);
        try {
            if (RocketMQConstants.ORDER_TAG_CREATE.equals(tag)) {
                VoucherOrder order = JSONUtil.toBean(body, VoucherOrder.class);
                voucherOrderService.createOrderFromMQ(order);
            } else if (RocketMQConstants.ORDER_TAG_TIMEOUT.equals(tag)) {
                voucherOrderService.cancelTimeoutOrder(Long.valueOf(body));
            } else {
                log.warn("未知消息 Tag: {}", tag);
            }
            return true;
        } catch (Exception e) {
            log.error("订单消息处理失败, tag={}, body={}", tag, body, e);
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
    }
}
