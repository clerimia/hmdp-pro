package com.hmdp.utils;

/**
 * 二级限流相关 Redis Key
 */
public class RateLimitConstants {

    private RateLimitConstants() {
    }

    /** 业务滑动窗口（领券）：rate:sw:seckill:{userId} */
    public static final String SLIDING_WINDOW_SECKILL_KEY = "rate:sw:seckill:";

    /**
     * 业务滑动窗口（结果轮询）：rate:sw:seckill:result:{userId}
     *
     * <p>与领券分开两个 key，是为了让两者各算各的配额：
     * 领券要严格（5 次/秒，挡连点与脚本），轮询要宽松但要封顶（10 次/秒）。
     * 共用一个 key 的话，前端多查几次结果就把自己的领券额度烧光了。
     */
    public static final String SLIDING_WINDOW_SECKILL_RESULT_KEY = "rate:sw:seckill:result:";
}
