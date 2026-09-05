package com.hmdp.dto;

/**
 * 秒杀券的活动窗口（毫秒时间戳），由预热服务从 tb_seckill_voucher 读出后写进 Redis，
 * 领券入口据此判断「未开始 / 进行中 / 已结束」。
 *
 * <p>存毫秒戳而不是 LocalDateTime 字符串，是为了让 Lua 与 Java 侧都能直接比较大小，
 * 不必在热路径上做时间解析。
 */
public class SeckillWindow {

    private final long beginMillis;

    private final long endMillis;

    public SeckillWindow(long beginMillis, long endMillis) {
        this.beginMillis = beginMillis;
        this.endMillis = endMillis;
    }

    public long getBeginMillis() {
        return beginMillis;
    }

    public long getEndMillis() {
        return endMillis;
    }

    public boolean isBeforeStart(long nowMillis) {
        return nowMillis < beginMillis;
    }

    /** 闭开区间：endTime 那一刻起视为已结束 */
    public boolean isAfterEnd(long nowMillis) {
        return nowMillis >= endMillis;
    }
}
