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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 领券订单消费者：消费 CREATE（异步落库）消息
 * 消费失败返回 RECONSUME_LATER，由 RocketMQ 按重试间隔自动重投，配合业务幂等保证不丢单
 *
 * <p>P2 容错：
 * <ul>
 *   <li><b>业务处理走独立线程池</b>：DB 慢时占满的是这里的业务线程 + 有界队列，
 *       队列打满立即拒绝整批重投——RocketMQ 拉取线程不被拖死，故障期是「快速拒」
 *       而不是「慢慢排队」。</li>
 *   <li><b>重试上限 5 次</b>：默认 16 次按 10s→2h 递增，总拖尾近 2 小时，故障持续
 *       超过几分钟就该交给对账补单兜底，而不是让重试队列无限拖尾。超限消息由
 *       Broker 自动转入死信 topic，由 {@link OrderDeadLetterConsumer} 监控打点。</li>
 * </ul>
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

    /**
     * 消费业务线程池（独立于 RocketMQ 内部线程）。
     * 有界队列 100 + AbortPolicy：DB 慢 → 池和队列打满 → submit 抛 RejectedExecutionException
     * → 整批延迟重投，这是故障期的自然背压，而不是把消息堆进无界队列慢慢消化。
     */
    private ThreadPoolExecutor consumeExecutor;

    /** 单条消息处理等待上限：覆盖 Hikari 连接超时 3s + 业务耗时，超时判失败走重投 */
    private static final long CONSUME_TASK_TIMEOUT_SECONDS = 20;

    /** 重试上限：5 次约 6 分钟（10s/30s/1m/2m/3m），超过即落死信，长故障交给对账 */
    private static final int MAX_RECONSUME_TIMES = 5;

    @PostConstruct
    public void init() throws Exception {
        consumeExecutor = new ThreadPoolExecutor(
                8, 16, 60, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100),
                new ThreadFactory() {
                    private final AtomicInteger idx = new AtomicInteger();

                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "order-consume-" + idx.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                new ThreadPoolExecutor.AbortPolicy());

        consumer = new DefaultMQPushConsumer(RocketMQConstants.ORDER_CONSUMER_GROUP);
        consumer.setNamesrvAddr(nameServer);
        // 消费者组首次启动时从最早位置消费；之后从上次消费位点续读
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        // 替换默认 16 次重试：超限后 Broker 把消息转入 %DLQ%order-consumer-group，
        // 避免故障期消息在重试队列无限拖尾
        consumer.setMaxReconsumeTimes(MAX_RECONSUME_TIMES);
        // 消费回调线程只做「提交任务 + 等待结果」，4 个足够；业务并发由独立池决定
        consumer.setConsumeThreadMin(4);
        consumer.setConsumeThreadMax(4);
        consumer.subscribe(RocketMQConstants.ORDER_TOPIC, RocketMQConstants.ORDER_TAG_CREATE);
        consumer.registerMessageListener((MessageListenerConcurrently) (msgs, context) -> {
            List<Future<Boolean>> futures = new ArrayList<>(msgs.size());
            try {
                for (MessageExt msg : msgs) {
                    futures.add(submitConsume(msg));
                }
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // 业务池打满（DB 慢/积压）：整批延迟重投。消费幂等，重复消费安全
                log.warn("消费线程池已满，本批 {} 条消息延迟重投", msgs.size());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
            for (Future<Boolean> future : futures) {
                try {
                    if (!future.get(CONSUME_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        // 消费失败稍后重试；业务处理本身幂等，重复消费安全
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                } catch (ExecutionException | TimeoutException e) {
                    // 任务异常 / 超时：判失败重投，避免卡死消费回调线程
                    log.error("消费任务异常或超时，延迟重投", e);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });
        consumer.start();
        log.info("RocketMQ 消费者启动成功，订阅 {}/CREATE，重试上限={} 次，业务线程池 core={} max={} queue=100",
                RocketMQConstants.ORDER_TOPIC, MAX_RECONSUME_TIMES, 8, 16);
    }

    /**
     * 提交单条消息到业务线程池。traceId 在「提交时刻」从消息 properties 提取，
     * 任务内 open → 处理 → finally clear（业务线程池化复用，不清理会日志串号）。
     * 发消息时的 MDC 到 RocketMQ 内部线程已经断掉，必须从消息显式恢复（重试消息带 -r{n} 后缀）。
     */
    private Future<Boolean> submitConsume(MessageExt msg) {
        String tag = msg.getTags();
        String traceId = MqTraceCarrier.extract(msg);
        return consumeExecutor.submit(() -> {
            TraceContext.open(traceId);
            try {
                boolean consumed = handleMessage(msg, tag);
                seckillMetrics.orderConsumed(tag, consumed);
                return consumed;
            } finally {
                TraceContext.clear();
            }
        });
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
            } else {
                log.warn("未知消息 Tag: {}", tag);
            }
            return true;
        } catch (Exception e) {
            // 含 dbBreaker 打开抛出的 CallNotPermittedException：快速失败延迟重投，
            // 不在故障期占用业务线程反复触碰 DB
            log.error("订单消息处理失败, tag={}, retryTimes={}, body={}",
                    tag, msg.getReconsumeTimes(), body, e);
            return false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (consumer != null) {
            consumer.shutdown();
        }
        if (consumeExecutor != null) {
            consumeExecutor.shutdown();
            try {
                if (!consumeExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    consumeExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                consumeExecutor.shutdownNow();
            }
        }
    }
}
