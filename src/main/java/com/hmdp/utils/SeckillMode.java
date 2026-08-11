package com.hmdp.utils;

/**
 * 秒杀方案模式与排队状态（开源双方案可切换）
 */
public final class SeckillMode {

    private SeckillMode() {
    }

    /** 方案 A：入口 Lua 预扣库存 + 事务消息，适合中小流量 / 要同步结果 */
    public static final String A = "A";
    /** 方案 B：入口只限流入队，校验下沉消费者，适合超大洪峰削峰 */
    public static final String B = "B";

    public static final String QUEUE_WAITING = "WAITING";
    public static final String QUEUE_SUCCESS = "SUCCESS";
    public static final String QUEUE_FAIL_STOCK = "FAIL_STOCK";
    public static final String QUEUE_FAIL_REPEAT = "FAIL_REPEAT";
    public static final String QUEUE_FAIL_SYSTEM = "FAIL_SYSTEM";
}
