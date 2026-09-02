package com.hmdp.observability;

import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.message.MessageExt;

/**
 * MQ 边界：traceId 的跨进程载体（可扩展点②）。
 *
 * <p><b>为什么放 properties 而不是 body</b>：traceId 是基础设施信息，body 是业务契约。
 * 塞进 body 会污染所有消费者的反序列化模型；properties 天生就是给这类元数据用的，
 * 和 HTTP header 是一个道理。
 *
 * <p><b>可扩展</b>：换 Kafka / RabbitMQ 时只需新增一个 carrier（读写各自的 header / properties），
 * {@code inject / extract} 这对方法签名不变，业务侧调用点不动。
 *
 * <p>注意区分：RocketMQ 自带的 {@code enableMsgTrace} 是 Broker 侧的消息轨迹（投递/消费耗时），
 * 与本类承载的业务 traceId 是互补关系，不是替代。
 */
public final class MqTraceCarrier {

    /** 与 HTTP 侧保持同一个 key，排查时不用记两套名字 */
    public static final String TRACE_ID_PROPERTY = "X-Trace-Id";

    /** 重试消息后缀：同一条消息的第 n 次消费，既保留串联关系又能区分每次尝试 */
    private static final String RETRY_SUFFIX = "-r%d";

    private MqTraceCarrier() {
    }

    /**
     * 发送前注入当前 traceId。当前线程没有开启链路时不注入（例如定时任务发出的消息）。
     */
    public static void inject(Message msg) {
        String traceId = TraceContext.current();
        if (traceId != null && !traceId.isEmpty()) {
            msg.putUserProperty(TRACE_ID_PROPERTY, traceId);
        }
    }

    /**
     * 消费端还原 traceId：取不到就现场生成（消费端也是一条独立链路的入口），
     * 重试消息追加 {@code -r{n}}，便于把「首次消费」和「第 3 次重试」的日志分开看。
     */
    public static String extract(MessageExt msg) {
        return extract(msg.getProperty(TRACE_ID_PROPERTY), msg.getReconsumeTimes());
    }

    /** 与具体 MQ 解耦的重载：给定原始 traceId 与重试次数即可 */
    public static String extract(String rawTraceId, int reconsumeTimes) {
        String traceId;
        if (rawTraceId == null || rawTraceId.trim().isEmpty()) {
            traceId = TraceContext.newTraceId();
        } else {
            traceId = rawTraceId.trim();
        }
        return reconsumeTimes > 0 ? traceId + String.format(RETRY_SUFFIX, reconsumeTimes) : traceId;
    }
}
