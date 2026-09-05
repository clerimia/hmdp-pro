package com.hmdp.observability;

/**
 * 埋点门面（可扩展点③）：业务代码只依赖这个接口。
 *
 * <p>当前实现是 {@link MicrometerRecorder}（对接 Prometheus）；将来换 OpenTelemetry、
 * 或压测时整体关闭，只需换一个实现类，所有埋点调用点一行都不用动。
 *
 * <p><b>没有「上报」动作</b>：Prometheus 是 pull 模型 —— 这里的 increment 只是内存里的
 * LongAdder 自增（纳秒级），由 {@code /actuator/prometheus} 端点暴露当前快照，
 * Prometheus server 按固定间隔来抓。秒杀这种高频路径如果逐条 push 到远端，
 * 埋点本身就会变成性能瓶颈和故障点。
 */
public interface ObservabilityRecorder {

    /**
     * 事件计数 +1。
     *
     * @param metric 指标名（点分命名，Prometheus 侧会自动转成 {@code xxx_total}；
     *               自己再带 {@code _total} 后缀会变成 {@code ..._total_total}）
     * @param tags   key,value 交替出现，<b>必须成对</b>；值只能取有限枚举。
     *               <p><b>红线</b>：绝不能拿 {@code orderId} / {@code userId} / {@code voucherId}
     *               / {@code shopId} / {@code traceId} 这类高基数值当 tag——每个唯一组合
     *               都是一条独立时间序列，会把采集端内存和查询一起打爆。它们只能进日志。
     */
    void increment(String metric, String... tags);

    /**
     * 开始计时。返回的句柄必须配合 {@link #stopTimer} 在 finally 中结束，否则会泄漏采样。
     */
    Sample startTimer();

    /** 结束计时并记录到指定指标 */
    void stopTimer(Sample sample, String metric, String... tags);

    /**
     * 计时句柄。刻意不暴露任何后端类型，避免业务代码被具体埋点实现污染。
     */
    interface Sample {
    }
}
