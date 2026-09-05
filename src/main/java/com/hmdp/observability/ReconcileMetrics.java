package com.hmdp.observability;

import org.springframework.stereotype.Component;

/**
 * 对账任务指标门面。与 {@link SeckillMetrics} 分开：对账不在请求路径上——
 * 秒杀指标是请求级（QPS/成功率/降级），对账是分钟级定时任务，时间尺度完全不同，
 * 塞进一张面板只会互相污染。与 {@link CacheMetrics} 同构，各自维护口径。
 *
 * <p>四个指标全是 Counter（点分命名，Micrometer 自动转 {@code xxx_total}，
 * 命名时<b>不要自己加 {@code _total} 后缀</b>，否则会变成 {@code ..._total_total}）：
 * <ul>
 *   <li>{@code hmdp.reconcile.round}{outcome=completed|skipped_supplement|skipped_lock}
 *       —— 任务心跳，每轮调度至多 +1；5 分钟无心跳即可告警</li>
 *   <li>{@code hmdp.reconcile.step}{step=supplement|restock, result=ok|error}
 *       —— 步骤级成败，error 是 runStep 吞掉的那个异常的唯一外部出口</li>
 *   <li>{@code hmdp.reconcile.supplement}{result=ok|error}
 *       —— <b>补发动作次数</b>，不是丢单笔数：同一笔丢单在收敛前会被重复计数
 *       （上轮补发的订单尚未落库时，下轮差集里还有它）。丢单笔数看日志</li>
 *   <li>{@code hmdp.reconcile.restock}{result=adjusted|converged}
 *       —— 券次判定：每轮每张在窗券 +1。adjusted&gt;0 = 发生过库存漂移</li>
 * </ul>
 *
 * <p><b>tag 红线</b>：{@code voucherId} / {@code shopId} / {@code userId} / {@code orderId}
 * / {@code traceId} 一律禁止进 tag——每个唯一 tag 组合都是一条独立时间序列，高基数会
 * 打爆 Prometheus。对账这里最想加的就是 {@code voucherId}（日志排查时最想要它），
 * 它只能进日志，不能进指标。
 */
@Component
public class ReconcileMetrics {

    public static final String RECONCILE_ROUND = "hmdp.reconcile.round";
    public static final String RECONCILE_STEP = "hmdp.reconcile.step";
    public static final String RECONCILE_SUPPLEMENT = "hmdp.reconcile.supplement";
    public static final String RECONCILE_RESTOCK = "hmdp.reconcile.restock";

    // —— tag 合法取值（与指标名一起收口在门面类，调用方不得自造字符串）——
    /** round 的 outcome：两步都尝试执行（步骤级成败看 step 指标，执行≠成功） */
    public static final String OUTCOME_COMPLETED = "completed";
    /** round 的 outcome：补过单或补单步骤异常，本轮跳过了库存重算 */
    public static final String OUTCOME_SKIPPED_SUPPLEMENT = "skipped_supplement";
    /** round 的 outcome：分布式锁抢锁失败，本轮没有执行 */
    public static final String OUTCOME_SKIPPED_LOCK = "skipped_lock";
    /** step 的取值：补单 */
    public static final String STEP_SUPPLEMENT = "supplement";
    /** step 的取值：库存重算 */
    public static final String STEP_RESTOCK = "restock";

    private final ObservabilityRecorder recorder;

    public ReconcileMetrics(ObservabilityRecorder recorder) {
        this.recorder = recorder;
    }

    /** 一轮对账的结局：completed（两步都执行）/ skipped_supplement（补过单或补单异常，跳过重算）/ skipped_lock（抢锁失败） */
    public void round(String outcome) {
        recorder.increment(RECONCILE_ROUND, "outcome", outcome);
    }

    /** 步骤级成败：step 取 supplement / restock，ok=false 即 result=error（runStep 吞掉的异常） */
    public void step(String step, boolean ok) {
        recorder.increment(RECONCILE_STEP, "step", step, "result", ok ? "ok" : "error");
    }

    /** 一次补发动作（同步落库调用）。ok=正常返回，error=抛异常 */
    public void supplement(boolean ok) {
        recorder.increment(RECONCILE_SUPPLEMENT, "result", ok ? "ok" : "error");
    }

    /** 一张在窗券一次重算的判定：adjusted=库存被改写（发生过漂移），converged=本来就一致 */
    public void restock(boolean adjusted) {
        recorder.increment(RECONCILE_RESTOCK, "result", adjusted ? "adjusted" : "converged");
    }
}
