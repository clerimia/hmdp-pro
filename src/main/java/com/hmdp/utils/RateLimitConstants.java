package com.hmdp.utils;

/**
 * 二级限流相关 Redis Key
 */
public class RateLimitConstants {

    private RateLimitConstants() {
    }

    /** 业务滑动窗口（秒杀下单）：rate:sw:seckill:{userId} */
    public static final String SLIDING_WINDOW_SECKILL_KEY = "rate:sw:seckill:";

    /**
     * 业务滑动窗口（结果轮询）：rate:sw:seckill:result:{userId}
     *
     * <p>与下单分开两个 key，是为了让两者各算各的配额：
     * 下单要严格（5 次/秒，挡连点与脚本），轮询要宽松但要封顶（10 次/秒）。
     * 共用一个 key 的话，前端多查几次结果就把自己的下单额度烧光了。
     */
    public static final String SLIDING_WINDOW_SECKILL_RESULT_KEY = "rate:sw:seckill:result:";

    /**
     * 业务滑动窗口（支付结果轮询）：rate:sw:order:pay:{userId}
     *
     * <p>与落库查询再分开的原因：两者生命周期不同。落库是秒级收敛（几秒内结束），
     * 支付要等用户操作、可能持续到 15 分钟关单窗口。共用配额会让「刚下完单正在查落库」
     * 和「下单很久了在等支付」互相干扰。
     */
    public static final String SLIDING_WINDOW_ORDER_PAY_KEY = "rate:sw:order:pay:";
}
