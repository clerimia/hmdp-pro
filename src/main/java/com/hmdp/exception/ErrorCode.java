package com.hmdp.exception;

/**
 * 错误码。
 *
 * <p><b>为什么要区分业务错误和系统错误</b>：熔断器的 {@code ignoreExceptions} 靠异常类型判断
 * 「哪些失败不算系统故障」。领券高峰期「库存不足」是最高频的结果，如果它和「Redis 超时」
 * 在系统里都是同一种异常，故障率会被业务结果污染，导致<strong>误熔断</strong>。
 *
 * <p>因此所有错误码按 {@link Category} 分成两类：
 * <ul>
 *   <li>{@link Category#BUSINESS} —— 业务结果，HTTP 200 + code，<b>不计入熔断</b></li>
 *   <li>{@link Category#SYSTEM}   —— 系统故障，HTTP 503，<b>计入熔断</b>，需带 traceId 排查</li>
 * </ul>
 *
 * <p>号段约定：1xxx 参数与业务；11xx 降级专用；5xxx 系统与依赖故障。
 */
public enum ErrorCode {

    // ---------------- 业务错误（1000+）：业务结果，不是故障 ----------------

    PARAM_INVALID(1001, "参数错误"),
    LOGIN_REQUIRED(1002, "请先登录"),
    STOCK_OUT(1003, "库存不足"),
    ORDER_REPEAT(1004, "不能重复领取"),
    ORDER_NOT_FOUND(1005, "订单不存在"),
    ORDER_CLOSED(1006, "订单已关闭"),
    /** 活动未到 beginTime：领券入口的时间闸门，缺失时活动还没开始就能被领光 */
    SECKILL_NOT_STARTED(1007, "活动尚未开始"),
    /** 活动已过 endTime */
    SECKILL_ENDED(1008, "活动已结束"),
    /**
     * 预热查不到该券时统一返回这个码。
     * 覆盖两种情况：券 id 根本不存在；券存在但 tb_seckill_voucher 里没记录（即普通券）。
     * 预热服务无法区分二者（都是 getById 返回 null），所以合成一个码。
     */
    VOUCHER_NOT_SECKILL(1009, "该优惠券不是秒杀券"),

    /**
     * 降级专用：Redis 预扣成功但订单尚未落库（DB 熔断打开、或 MQ 积压）。
     *
     * <p>此时<b>不能返回成功</b>——订单还没真正写进数据库，最终由对账任务补齐；
     * 谎报成功会让用户看到「领取成功但订单消失」。统一返回这个码，前端展示「处理中」。
     */
    ORDER_PROCESSING(1100, "领取处理中，请稍后查看"),

    // ---------------- 系统错误（5000+）：依赖故障，计入熔断 ----------------

    /** 熔断打开 / 舱壁已满：主动快速失败，而不是让请求排队等死 */
    SYS_BUSY(5001, "活动火爆，请稍后重试"),
    SYS_REDIS_UNAVAILABLE(5002, "缓存服务暂时不可用"),
    SYS_DB_UNAVAILABLE(5003, "订单服务暂时不可用"),
    SYS_MQ_UNAVAILABLE(5004, "领取通道暂时不可用"),
    SYS_ERROR(5999, "系统异常，请稍后重试");

    private final int code;
    private final String message;
    private final Category category;

    ErrorCode(int code, String message, Category category) {
        this.code = code;
        this.message = message;
        this.category = category;
    }

    ErrorCode(int code, String message) {
        this(code, message, code < 5000 ? Category.BUSINESS : Category.SYSTEM);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public Category getCategory() {
        return category;
    }

    /** 是否为业务错误（业务错误不参与熔断计数） */
    public boolean isBusiness() {
        return category == Category.BUSINESS;
    }

    public enum Category {
        /** 业务结果：正常返回，不熔断 */
        BUSINESS,
        /** 系统故障：快速失败并熔断 */
        SYSTEM
    }
}
