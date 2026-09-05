package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;

    public static final Long CACHE_NULL_TTL = 2L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    /** 秒杀券活动元信息 hash（字段 begin/end = 毫秒时间戳），入口校验活动窗口用 */
    public static final String SECKILL_META_KEY = "seckill:meta:";
    /** 活动元信息 TTL（小时）：活动信息基本静态，长 TTL 减少回源；到期自动重新预热 */
    public static final long SECKILL_META_TTL_HOURS = 24L;
    /** 预热互斥锁前缀：缓存击穿时只放行一个线程回源 DB */
    public static final String LOCK_SECKILL_WARM_KEY = "lock:seckill:warm:";
    /** 秒杀成功 claim 用户集合（Lua 脚本 SADD 写入，一人一单与补单差集依据） */
    public static final String SECKILL_ORDER_KEY = "seckill:order:";
    /**
     * 原订单号认领映射（hash：voucherId → {userId: orderId}），Lua 与扣库存同脚本原子写入。
     * 对账补单据此复用用户入口拿到的原 orderId——换新号补单会让用户轮询旧单号永远 NOT_FOUND
     * （表现为「领到了但订单消失」）。TTL 14d 由 Lua 每次写滑动续期，覆盖 7d 对账补单窗口；
     * 超过 14d 且静默期超 14d 的活动会退化为新号补单（与存量数据同路径）。
     */
    public static final String SECKILL_CLAIM_KEY = "seckill:claim:";
    public static final long SECKILL_CLAIM_TTL_SECONDS = 1209600L;
    /** RocketMQ 事务消息本地标记（与 Lua 扣库存同脚本写入，回查用） */
    public static final String SECKILL_TXN_KEY = "seckill:txn:";
    /** 事务标记 TTL（秒），需覆盖 Broker 回查窗口 */
    public static final long SECKILL_TXN_TTL_SECONDS = 3600L;
    /** 方案 B 排队/结果状态 seckill:queue:{orderId} */
    public static final String SECKILL_QUEUE_KEY = "seckill:queue:";
    /** 排队状态 TTL（分钟） */
    public static final long SECKILL_QUEUE_TTL_MINUTES = 5L;
    /**
     * 排队状态「空值标记」TTL（秒）：查无此单时回写 {@code seckill:queue:{orderId} = NOT_FOUND}，
     * 让同一个不存在的 orderId 在 TTL 内不再穿透到 DB。
     *
     * <p>只防「同一个」伪造 orderId 的反复查询；换号靠结果查询的滑动窗口限流挡。
     * TTL 取 10s 而不是分钟级，是为了误判能自愈：排队状态写入失败 + 落库尚未完成时，
     * 一次轮询会被误标成 NOT_FOUND，短 TTL 让用户等几秒再查就能拿到正确结果。
     */
    public static final long SECKILL_QUEUE_NULL_TTL_SECONDS = 10L;
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
}
