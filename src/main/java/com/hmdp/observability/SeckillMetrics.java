package com.hmdp.observability;

import org.springframework.stereotype.Component;

/**
 * 秒杀链路的关键事件门面：把「指标名 + tag 名 + 合法取值」集中到一处。
 *
 * <p>业务代码只调用语义化方法（{@code finishSeckill / rateLimitFallback / orderConsumed}），
 * 不直接拼指标名和 tag —— 想新增事件就在这里加一个方法，指标口径不会散落各处。
 *
 * <p><b>命名</b>：这里用点分命名，Micrometer 会按 Prometheus 约定自动转换：
 * Counter {@code hmdp.seckill.result} → {@code hmdp_seckill_result_total}；
 * Timer {@code hmdp.seckill.latency} → {@code hmdp_seckill_latency_seconds_*}。
 * 所以 Timer 名不要自己加 {@code _seconds} 后缀，否则会变成 {@code ..._seconds_seconds}。
 */
@Component
public class SeckillMetrics {

    public static final String SECKILL_REQUEST = "hmdp.seckill.request";
    public static final String SECKILL_RESULT = "hmdp.seckill.result";
    public static final String SECKILL_LATENCY = "hmdp.seckill.latency";
    public static final String RATE_LIMIT_FALLBACK = "hmdp.ratelimit.fallback";
    public static final String ORDER_CONSUME = "hmdp.order.consume";

    private final ObservabilityRecorder recorder;

    public SeckillMetrics(ObservabilityRecorder recorder) {
        this.recorder = recorder;
    }

    /** 进入秒杀接口（限流之前计数，用于算被限流比例） */
    public void seckillRequested(String mode) {
        recorder.increment(SECKILL_REQUEST, "mode", mode);
    }

    /** 开始计时，配合 {@link #finishSeckill} */
    public ObservabilityRecorder.Sample startSeckill() {
        return recorder.startTimer();
    }

    /**
     * 结束一次秒杀并落结果计数。
     *
     * @param sample 可为 null —— 被限流拦截时请求还没进入业务方法，没有计时句柄
     */
    public void finishSeckill(ObservabilityRecorder.Sample sample, String mode, Reason reason) {
        if (sample != null) {
            recorder.stopTimer(sample, SECKILL_LATENCY, "mode", mode, "result", reason.result());
        }
        recorder.increment(SECKILL_RESULT,
                "mode", mode, "result", reason.result(), "reason", reason.getCode());
    }

    /** 限流组件自身不可用或触发限流。strategy 现阶段只取 fail_open / rejected */
    public void rateLimitFallback(String strategy) {
        recorder.increment(RATE_LIMIT_FALLBACK, "strategy", strategy);
    }

    /** MQ 消息消费结果，tag 取消息 Tag（CREATE / TIMEOUT） */
    public void orderConsumed(String tag, boolean success) {
        recorder.increment(ORDER_CONSUME, "tag", tag, "result", success ? "ok" : "error");
    }

    /**
     * 失败原因枚举（可扩展点④）：tag 值封闭在这个集合内。
     *
     * <p><b>红线</b>：绝不能用 orderId / userId / traceId 当 tag ——
     * Prometheus 里每个唯一的 tag 组合都是一条独立时间序列，高基数值会把内存和查询一起打爆。
     * 单笔明细用带 traceId 的日志查，不归指标管。
     */
    public enum Reason {

        SUCCESS(true, "success"),
        STOCK_OUT(false, "stock_out"),
        REPEAT(false, "repeat"),
        RATE_LIMITED(false, "rate_limited"),
        MQ_SEND_ERROR(false, "mq_send_error"),
        SYSTEM_ERROR(false, "system_error");

        private final boolean success;

        private final String code;

        Reason(boolean success, String code) {
            this.success = success;
            this.code = code;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getCode() {
            return code;
        }

        /** 粗粒度结果，用于算成功率 */
        public String result() {
            return success ? "success" : "fail";
        }
    }
}
