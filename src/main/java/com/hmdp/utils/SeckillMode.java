package com.hmdp.utils;

/**
 * 领券方案标识与排队状态。
 *
 * <p><b>曾有过两种写路径（A/B），现只保留 A。</b>
 * 原方案 B 是「入口只限流入队、库存与一人一单校验下沉到消费者」，
 * 削峰靠 MQ 堆积。删除它的原因：
 * <ul>
 *   <li>两条路径共用一套落库与对账代码，消费端到处是 {@code if (modeB)} 分支，
 *       改动任一路径都要在脑内跑两遍推演，维护成本高于收益；</li>
 *   <li>方案 A 的入口 Lua 已经把库存与一人一单校验完了，B 的「校验下沉」
 *       只是把同样的判断换个地方做，削峰能力可以由网关令牌桶 + 应用层滑动窗口承担；</li>
 *   <li>前端（hm-dianping 静态页）从未实现 B 所需的轮询，B 路径实际上没人走完整。</li>
 * </ul>
 * 保留 {@link #A} 是因为它同时是指标的 {@code mode} tag 值，去掉会让历史面板断档。
 */
public final class SeckillMode {

    private SeckillMode() {
    }

    /** 唯一写路径：入口 Lua 预扣库存与一人一单 + 事务消息异步落库 */
    public static final String A = "A";

    /** 已入队/已预扣成功，等待消费者落库 */
    public static final String QUEUE_WAITING = "WAITING";
    /** 落库成功 */
    public static final String QUEUE_SUCCESS = "SUCCESS";
    /** 落库时发现库存不足 */
    public static final String QUEUE_FAIL_STOCK = "FAIL_STOCK";
    /** 落库时发现重复领取 */
    public static final String QUEUE_FAIL_REPEAT = "FAIL_REPEAT";
    /** 系统故障导致未能落库 */
    public static final String QUEUE_FAIL_SYSTEM = "FAIL_SYSTEM";
    /** 查不到该订单：排队状态与订单表都没有（TTL 过期、写入失败，或 orderId 是伪造的） */
    public static final String QUEUE_NOT_FOUND = "NOT_FOUND";
    /**
     * 状态未知：排队状态缺失，且订单表此刻查不动（DB 熔断/故障）。
     *
     * <p>这个状态是混沌测试挖出来的：DB 故障恰恰是用户最需要轮询的时刻——
     * 入口降级返回 ORDER_PROCESSING 并给了一个 orderId，用户只能靠轮询确认结果。
     * 但轮询的兜底路径就是查订单表，DB 一挂它自己也挂，原实现直接抛 500。
     * 给用户 500 等于告诉他「彻底失败了，别等了」，而实际上订单可能马上就落库
     * （实测 DB 恢复后约 90 秒全部自动追平）。
     *
     * <p>返回 UNKNOWN 让前端继续轮询，而不是把可恢复的等待判成终态失败。
     * 它与 NOT_FOUND 的区别很关键：NOT_FOUND 是「确定没有」，UNKNOWN 是「暂时不知道」。
     */
    public static final String QUEUE_UNKNOWN = "UNKNOWN";
}
