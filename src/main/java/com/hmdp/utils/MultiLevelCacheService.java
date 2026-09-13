package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.ObservabilityConfig;
import com.hmdp.observability.CacheMetrics;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * 多级缓存服务：Caffeine（一级）→ Redis（二级）→ MySQL（三级）
 *
 * <pre>
 *   命中率递减、速度递减、成本递减
 *   Caffeine 纳秒级（JVM 内存）
 *   → Redis 毫秒级（网络 IO）
 *   → MySQL 毫秒~秒级（磁盘 IO）
 * </pre>
 *
 * <p><b>缓存值不可变约定</b>：L1/L2 存的是反序列化后的共享对象引用（同 key 的所有
 * 请求拿到同一实例），调用方<b>不得修改</b>返回值或缓存对象——任何 in-place 修改
 * 会直接污染两级缓存。不做防御性拷贝是性能取舍（每次 put/get 走序列化会吃掉
 * 本地缓存的纳秒级优势）。
 */
@Slf4j
@Component
public class MultiLevelCacheService {

    /** 锁使用显式租期，避免线程异常后长期占用（禁用 watchdog）。 */
    private static final long MUTEX_LOCK_LEASE_SECONDS = 30;
    private static final long REBUILD_LOCK_LEASE_SECONDS = 30;
    /** 有界等待重建方写回的上限与轮询间隔。 */
    private static final long MUTEX_WAIT_MILLIS = 1000;
    private static final long MUTEX_POLL_INTERVAL_MILLIS = 50;
    /** TTL 扰动及物理过期倍数，用于降低缓存雪崩和脏数据驻留风险。 */
    private static final int NULL_TTL_JITTER_MINUTES = 3;
    private static final double TTL_JITTER_RATIO = 0.2;
    private static final long PHYSICAL_TTL_MULTIPLIER = 3;

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final Cache<String, Object> shopLocalCache;
    private final AsyncTaskExecutor rebuildExecutor;
    private final CacheMetrics cacheMetrics;

    /** 本 JVM 内先按 key 去重，Redisson 锁再负责多实例去重。 */
    private final Set<String> rebuildingKeys = ConcurrentHashMap.newKeySet();

    public MultiLevelCacheService(
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            @Qualifier("shopLocalCache") Cache<String, Object> shopLocalCache,
            @Qualifier(ObservabilityConfig.TRACE_AWARE_EXECUTOR) AsyncTaskExecutor rebuildExecutor,
            CacheMetrics cacheMetrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.shopLocalCache = shopLocalCache;
        this.rebuildExecutor = rebuildExecutor;
        this.cacheMetrics = cacheMetrics;
    }

    // ==================== 公开 API ====================

    /**
     * 多级缓存查询（穿透保护 + 逻辑过期防击穿）
     *
     * <p>{@code @Retry(cacheQueryRetry)}：只读幂等路径才配重试（2 次、100ms 指数退避）。
     * 放在这一层而不是 ShopServiceImpl 上是有意的——R4J 切面顺序 Retry 在 CircuitBreaker
     * 外层，如果把 Retry 和 fallback 放同一个方法，fallback 会把异常"消化"成正常返回，
     * Retry 一次也触发不了；分层后顺序变成 熔断(外) → 重试(内) → 缓存查询。
     * 写路径绝不加 Retry（领券重试可能重复扣库存）。
     */
    @Retry(name = "cacheQueryRetry")
    public <R, ID> R queryWithMultiLevel(
            String keyPrefix,
            ID id,
            Class<R> resultType,
            Function<ID, R> dbFallback,
            Long ttl,
            TimeUnit unit) {
        return queryWithMultiLevelVersioned(
                keyPrefix, id, resultType, dbFallback, ttl, unit, null, null);
    }

    /**
     * 版本感知变体：写回前核验快照版本，治"成功的脏写"。
     *
     * <p><b>竞态</b>：回查 DB 与写回缓存是两个 separated 动作，中间空隙里运营可能提交
     * 更新并删缓存——旧快照随后被写回，且带着崭新的逻辑过期时间和重置的物理 TTL
     * （等于给脏数据续命），系统外观一切健康。
     *
     * <p><b>为什么比对 DB 而不是 Redis 现有值</b>：删除式 Cache-Aside 下更新方只删不写，
     * 竞态最致命的形态恰好发生在 key 被删之后——Redis 里没有现值可比，比对形同虚设。
     * 版本权威必须取自唯一不会消失的真相源（DB 的 update_time，
     * 由 ON UPDATE CURRENT_TIMESTAMP 自动维护，天然是行级版本号）。
     *
     * <p>该版本感知入口直接带 {@code @Retry}，调用侧必须经 Spring Bean 调用，
     * 避免同类内部调用绕过 AOP 代理。
     *
     * @param snapshotVersionOf    从回查结果取版本（如 {@code Shop::getUpdateTime}），null = 关闭核验
     * @param currentVersionLoader 查 DB 当前行版本，null = 关闭核验
     */
    @Retry(name = "cacheQueryRetry")
    public <R, ID> R queryWithMultiLevelVersioned(
            String keyPrefix,
            ID id,
            Class<R> resultType,
            Function<ID, R> dbFallback,
            Long ttl,
            TimeUnit unit,
            Function<R, LocalDateTime> snapshotVersionOf,
            Function<ID, LocalDateTime> currentVersionLoader) {

        String cacheKey = buildCacheKey(keyPrefix, id);

        R local = getFromLocalCache(cacheKey);
        if (local != null) {
            cacheMetrics.hit(CacheMetrics.LEVEL_L1);
            log.debug("[多级缓存] L1 Caffeine 命中: {}", cacheKey);
            return local;
        }

        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            R data = deserializeCachedValue(redisData, resultType);
            LocalDateTime expireTime = redisData.getExpireTime();

            cacheMetrics.hit(CacheMetrics.LEVEL_L2);
            if (expireTime.isAfter(LocalDateTime.now())) {
                shopLocalCache.put(cacheKey, data);
                log.debug("[多级缓存] L2 Redis 命中: {}", cacheKey);
                return data;
            }
            // 逻辑过期 → 异步重建（返回旧数据不阻塞请求）。旧值只服务当前请求，不进入普通
            // 30s L1；否则 Redis 已重建后仍会被 L1 挡住，删除场景下还可能发生
            // "重建线程清 L1、请求线程随后写回幽灵旧值" 的竞态。
            rebuildAsync(keyPrefix, id, dbFallback, ttl, unit, snapshotVersionOf, currentVersionLoader);
            return data;
        }

        // 空值防穿透：空串标记也是从 Redis 取得的有效响应，同样计入 L2 命中
        // （口径：所有「从 Redis 取得响应且未落 DB」的读均计 L2，否则看板会低估 L2 命中率）
        if (json != null) {
            cacheMetrics.hit(CacheMetrics.LEVEL_L2);
            return null;
        }

        cacheMetrics.hit(CacheMetrics.LEVEL_DB);
        R result = queryWithMutexLock(keyPrefix, id, resultType, dbFallback, ttl, unit,
                snapshotVersionOf, currentVersionLoader);
        if (result != null) {
            shopLocalCache.put(cacheKey, result);
        }
        return result;
    }

    public void evict(String keyPrefix, Object id) {
        String key = buildCacheKey(keyPrefix, id);
        shopLocalCache.invalidate(key);
        stringRedisTemplate.delete(key);
        log.debug("[多级缓存] 已清除: {}", key);
    }

    // ==================== 内部实现 ====================

    /**
     * SETNX 互斥锁查库（只在 L1/L2 全部未命中时进入——此时缓存里没有任何"旧值"可返回）。
     *
     * <p>拿不到锁 = 别的线程正在回源。商铺缓存一致性要求低，采用<b>有界等待</b>：
     * 轮询缓存最多 {@link #MUTEX_WAIT_MILLIS}，对方写回即返回；超限后自己回源 DB 兜底。
     * 不做无界递归/无限等待——重建方一旦卡住，等待方线程会被全部占死。
     */
    private <R, ID> R queryWithMutexLock(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit,
            Function<R, LocalDateTime> snapshotVersionOf,
            Function<ID, LocalDateTime> currentVersionLoader) {

        String key = buildCacheKey(keyPrefix, id);
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);

        boolean locked = false;
        try {
            // wait=0（拿不到走下面的有界轮询）、lease=30s 硬上限（不含 watchdog）
            locked = lock.tryLock(0, MUTEX_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (locked) {
            try {
                // 双重检查：拿到锁后再查一次缓存，防止锁等待期间对方已完成重建。
                // 命中同样计 L2——它是真实的 Redis 读响应，漏计会低估命中率
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    cacheMetrics.hit(CacheMetrics.LEVEL_L2);
                    return deserializeCachedValue(json, type);
                }
                return loadAndCache(key, id, dbFallback, ttl, unit,
                        snapshotVersionOf, currentVersionLoader);
            } finally {
                // 仅当前线程持有时释放，避免递归重试路径误删他人持有的锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // 拿不到锁：有界轮询等待重建方写回
        long deadline = System.currentTimeMillis() + MUTEX_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    cacheMetrics.hit(CacheMetrics.LEVEL_L2);
                    return deserializeCachedValue(json, type);
                }
                if (json != null) {
                    cacheMetrics.hit(CacheMetrics.LEVEL_L2); // 空值标记同样是 L2 响应
                    return null; // 数据真不存在，不必再等
                }
            } catch (Exception e) {
                break; // Redis 不可用：跳出等待交给上层熔断/降级，不在循环里空转
            }
            try {
                Thread.sleep(MUTEX_POLL_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 等待超限（重建方卡住 >1s）：自己回源 DB。多这一个并发读的代价，
        // 远小于把请求线程无限挂住，也远好于对用户谎报"店铺不存在"
        return dbFallback.apply(id);
    }

    private static String buildCacheKey(String keyPrefix, Object id) {
        return keyPrefix + id;
    }

    @SuppressWarnings("unchecked")
    private <R> R getFromLocalCache(String key) {
        return (R) shopLocalCache.getIfPresent(key);
    }

    private <R> R deserializeCachedValue(String json, Class<R> resultType) {
        return deserializeCachedValue(JSONUtil.toBean(json, RedisData.class), resultType);
    }

    private <R> R deserializeCachedValue(RedisData redisData, Class<R> resultType) {
        return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), resultType);
    }

    /**
     * 持锁回源：查 DB → 空值防穿透 / 写逻辑过期缓存。
     * 写回前核验快照版本：竞态下（读 DB 后、写回前恰有更新提交并删缓存）放弃写回，
     * 避免旧快照带着崭新的逻辑过期时间与重置的物理 TTL 被写进 Redis。
     */
    private <R, ID> R loadAndCache(
            String key, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit,
            Function<R, LocalDateTime> snapshotVersionOf,
            Function<ID, LocalDateTime> currentVersionLoader) {
        R result = dbFallback.apply(id);
        if (result == null) {
            long randomTtl = CACHE_NULL_TTL
                    + ThreadLocalRandom.current().nextInt(NULL_TTL_JITTER_MINUTES);
            stringRedisTemplate.opsForValue().set(key, "", randomTtl, TimeUnit.MINUTES);
            return null;
        }
        // 版本核验：快照落后于 DB → 不写缓存，只把数据返回给当前请求。
        // key 此时多半已被 evict 删掉，下一个请求会 miss → 互斥锁回源，拿到必然新鲜的值
        if (isStaleSnapshot(id, result, snapshotVersionOf, currentVersionLoader)) {
            return result;
        }
        writeWithLogicalExpire(key, result, ttl, unit);
        return result;
    }

    private <R, ID> void rebuildAsync(
            String keyPrefix, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit,
            Function<R, LocalDateTime> snapshotVersionOf,
            Function<ID, LocalDateTime> currentVersionLoader) {

        String cacheKey = buildCacheKey(keyPrefix, id);
        if (!rebuildingKeys.add(cacheKey)) {
            return;
        }
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        try {
            rebuildExecutor.submit(() -> {
                try {
                    // 在重建线程内加锁，保证锁的持有与释放为同一线程；
                    // 显式 lease=30s 禁用 watchdog，重建线程挂死时锁最迟 30s 自动释放
                    boolean locked;
                    try {
                        locked = lock.tryLock(0, REBUILD_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    if (!locked) {
                        return; // 其他实例正在重建
                    }
                    try {
                        R data = dbFallback.apply(id);
                        if (data == null) {
                            stringRedisTemplate.delete(cacheKey);
                            shopLocalCache.invalidate(cacheKey);
                        } else if (!isStaleSnapshot(id, data, snapshotVersionOf, currentVersionLoader)) {
                            writeWithLogicalExpire(cacheKey, data, ttl, unit);
                        }
                        cacheMetrics.rebuilt(true);
                    } catch (Exception e) {
                        cacheMetrics.rebuilt(false);
                        log.error("[多级缓存] 异步重建失败: {}", cacheKey, e);
                    } finally {
                        if (lock.isHeldByCurrentThread()) {
                            lock.unlock();
                        }
                    }
                } finally {
                    rebuildingKeys.remove(cacheKey);
                }
            });
        } catch (RuntimeException e) {
            rebuildingKeys.remove(cacheKey);
            throw e;
        }
    }

    /**
     * 写回侧版本核验：快照的 update_time 落后于 DB 当前行 → 判定为脏，放弃写回。
     *
     * <p>核验失败的后果是安全的：key 要么已被 evict 删掉（下一请求 miss → 互斥锁回源，
     * 拿到必然新鲜的值），要么还留着未过期的旧值（下一个逻辑过期周期重建自愈）。
     * 核验本身只查一列（PK 查询），发生在每次重建/回源时，频率低、成本可忽略。
     *
     * <p>残余窗口如实声明：查版本与写入之间理论上仍可插入更新，概率比原竞态低
     * 数量级，接受；要绝对严格需把"读-比-写"整体串行化，代价不成比例。
     */
    private <R, ID> boolean isStaleSnapshot(
            ID id, R snapshot,
            Function<R, LocalDateTime> snapshotVersionOf,
            Function<ID, LocalDateTime> currentVersionLoader) {
        if (snapshotVersionOf == null || currentVersionLoader == null) {
            return false; // 未启用版本核验（非实体缓存或调用方未提供）
        }
        LocalDateTime snapVer = snapshotVersionOf.apply(snapshot);
        if (snapVer == null) {
            return false; // 快照无版本字段，退回旧行为
        }
        LocalDateTime dbVer = currentVersionLoader.apply(id);
        if (dbVer == null) {
            return false; // 行可能已被删除，保守按旧行为处理（写回交给后续 evict/TTL 收敛）
        }
        boolean stale = snapVer.isBefore(dbVer);
        if (stale) {
            cacheMetrics.staleSkip();
            log.info("[多级缓存] 快照版本落后，跳过写回: id={}, snapVer={}, dbVer={}",
                    id, snapVer, dbVer);
        }
        return stale;
    }

    private void writeWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 随机 TTL ± 20%，避免同时过期引发雪崩
        long baseSec = unit.toSeconds(ttl);
        long jitter = (long) (baseSec * TTL_JITTER_RATIO
                * ThreadLocalRandom.current().nextDouble());
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(baseSec + jitter));
        // 物理 TTL 保险丝：不参与正常过期判断（那是 expireTime 的职责）。逻辑过期把过期判断
        // 挪进了应用层，Redis 侧这个 key 本身永不过期；一旦主动删除与异步重建同时失败，
        // 脏数据将无限期驻留。这里保证最迟 3 倍逻辑 TTL 内自愈，把 ∞ 变成有限值。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),
                baseSec * PHYSICAL_TTL_MULTIPLIER, TimeUnit.SECONDS);
    }
}
