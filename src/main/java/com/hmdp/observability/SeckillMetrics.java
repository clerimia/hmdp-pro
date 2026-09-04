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
    public static final String ORDER_TIMEOUT_SEND_ERROR = "hmdp.order.timeout_send_error";
    public static final String ORDER_DEAD_LETTER = "hmdp.order.dead_letter";
    public static final String DEGRADED = "hmdp.seckill.degraded";

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

    /** 超时关单延迟消息发送失败（已记入 Redis 重试集合，由对账任务重发） */
    public void orderTimeoutSendError() {
        recorder.increment(ORDER_TIMEOUT_SEND_ERROR);
    }

    /** 订单消息超过重试上限落入死信 topic：重试链路已放弃，等待对账/人工介入 */
    public void orderDeadLetter() {
        recorder.increment(ORDER_DEAD_LETTER);
    }

    /**
     * 降级量（P3）：按 breaker 维度计入既有 reason 体系，Grafana 用
     * {@code increase(hmdp_seckill_degraded_total[1m])} 看每分钟降级量。
     *
     * @param breaker 触发降级的韧性实例（dbBreaker / mqBreaker）
     * @param reason  降级在 reason 体系里的对应项（db_degraded / mq_send_error），
     *                与 {@code hmdp.seckill.result} 同口径，两张图可以互相印证
     */
    public void degraded(String breaker, Reason reason) {
        recorder.increment(DEGRADED, "breaker", breaker, "reason", reason.getCode());
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
        /** dbBreaker 打开/半开，落库无期，入口诚实返回「下单处理中」而非成功 */
        DB_DEGRADED(false, "db_degraded"),
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
