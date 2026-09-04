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

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
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
 */
@Slf4j
@Component
public class MultiLevelCacheService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private Cache<String, Object> shopLocalCache;

    /**
     * 逻辑过期异步重建线程池：统一用容器里的 {@code traceAwareExecutor}
     * （挂了 {@link com.hmdp.observability.MdcTaskDecorator}），
     * 否则重建任务的日志与发起请求的那条链路是断的。
     */
    @Resource
    @Qualifier(ObservabilityConfig.TRACE_AWARE_EXECUTOR)
    private AsyncTaskExecutor rebuildExecutor;

    @Resource
    private CacheMetrics cacheMetrics;

    /** 随机 TTL 因子，避免缓存雪崩 */
    private static final Random RANDOM = new Random();

    /**
     * 缓存击穿互斥锁租期（秒）：DB 回源 + 写缓存的上界（不含 watchdog） */
    private static final long MUTEX_LOCK_LEASE_SECONDS = 30;
    /** 缓存重建锁租期（秒）：显式 lease 禁用 watchdog，重建线程挂死时锁自动释放 */
    private static final long REBUILD_LOCK_LEASE_SECONDS = 30;
    /**
     * 击穿互斥锁的有界等待时长（ms）：拿不到锁时轮询缓存等重建方写回的上限。
     * 等待必须收敛——重建方卡住时，无界递归/无限等待会把请求线程全部占死，
     * 和 Redis 800ms 命令超时是同一条收敛原则。
     */
    private static final long MUTEX_WAIT_MILLIS = 1000;

    // ==================== 公开 API ====================

    /**
     * 多级缓存查询（穿透保护 + 逻辑过期防击穿）
     *
     * <p>{@code @Retry(cacheQueryRetry)}：只读幂等路径才配重试（2 次、100ms 指数退避），
     * 瞬时抖动自愈。放在这一层而不是 ShopServiceImpl 上是有意的——R4J 切面顺序
     * Retry 在 CircuitBreaker 外层，如果把 Retry 和 fallback 放同一个方法，
     * fallback 会把异常"消化"成正常返回，Retry 一次也触发不了；分层后顺序变成
     * 熔断(外) → 重试(内) → 缓存查询，两次重试都失败才交给外层熔断 + 降级回源。
     * 写路径绝不加 Retry（下单重试可能重复扣库存）。
     *
     * @param keyPrefix Redis key 前缀
     * @param id        业务 ID
     * @param type      返回类型
     * @param dbFallback 查库函数
     * @param ttl       缓存时间
     * @param unit      时间单位
     */
    @Retry(name = "cacheQueryRetry")
    @SuppressWarnings("unchecked")
    public <R, ID> R queryWithMultiLevel(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String cacheKey = keyPrefix + id;

        // ── 第一层：Caffeine 本地缓存 ──
        R local = (R) shopLocalCache.getIfPresent(cacheKey);
        if (local != null) {
            cacheMetrics.hit(CacheMetrics.LEVEL_L1);
            log.debug("[多级缓存] L1 Caffeine 命中: {}", cacheKey);
            return local;
        }

        // ── 第二层：Redis ──
        String json = stringRedisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(json)) {
            // 检查逻辑过期
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            R data = JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();

            cacheMetrics.hit(CacheMetrics.LEVEL_L2);
            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期 → 写回 Caffeine，返回
                shopLocalCache.put(cacheKey, data);
                log.debug("[多级缓存] L2 Redis 命中: {}", cacheKey);
                return data;
            }

            // 逻辑过期 → 异步重建（返回旧数据不阻塞请求）
            rebuildAsync(keyPrefix, id, dbFallback, ttl, unit);
            // 返回旧数据
            shopLocalCache.put(cacheKey, data);
            return data;
        }

        // 空值防穿透
        if (json != null) {
            return null;
        }

        // ── 第三层：查 MySQL（互斥锁防击穿）──
        cacheMetrics.hit(CacheMetrics.LEVEL_DB);
        R result = queryWithMutexLock(keyPrefix, id, type, dbFallback, ttl, unit);
        if (result != null) {
            shopLocalCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 删除所有级别的缓存（写操作时调用）
     */
    public void evict(String keyPrefix, Object id) {
        String key = keyPrefix + id;
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
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = keyPrefix + id;
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);

        boolean locked = false;
        try {
            // 显式 wait/lease：wait=0（拿不到走下面的有界轮询）、lease=30s 硬上限（不含 watchdog）
            locked = lock.tryLock(0, MUTEX_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (locked) {
            try {
                // 双重检查：拿到锁后再查一次缓存，防止锁等待期间对方已完成重建
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    return fromLogicalExpireJson(json, type);
                }
                return loadAndCache(key, id, dbFallback, ttl, unit);
            } finally {
                // 仅当前线程持有时释放，避免递归重试路径误删他人持有的锁
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        // ── 拿不到锁：有界轮询等待重建方写回 ──
        long deadline = System.currentTimeMillis() + MUTEX_WAIT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                String json = stringRedisTemplate.opsForValue().get(key);
                if (StrUtil.isNotBlank(json)) {
                    return fromLogicalExpireJson(json, type);
                }
                if (json != null) {
                    return null; // 空值防穿透标记：数据真不存在，不必再等
                }
            } catch (Exception e) {
                break; // Redis 不可用：跳出等待交给上层熔断/降级，不在循环里空转
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 等待超限（重建方卡住 >1s）：自己回源 DB。多这一个并发读的代价，
        // 远小于把请求线程无限挂住，也远好于对用户谎报"店铺不存在"
        return dbFallback.apply(id);
    }

    /** 解析逻辑过期格式的缓存 JSON */
    @SuppressWarnings("unchecked")
    private <R> R fromLogicalExpireJson(String json, Class<R> type) {
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
    }

    /** 持锁回源：查 DB → 空值防穿透 / 写逻辑过期缓存 */
    private <R, ID> R loadAndCache(
            String key, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {
        R result = dbFallback.apply(id);
        if (result == null) {
            // 空值缓存防穿透，加随机 TTL
            long randomTtl = CACHE_NULL_TTL + RANDOM.nextInt(3);
            stringRedisTemplate.opsForValue().set(key, "", randomTtl, TimeUnit.MINUTES);
            return null;
        }
        // 写入 Redis（逻辑过期模式）
        writeWithLogicalExpire(key, result, ttl, unit);
        return result;
    }

    /**
     * 异步重建过期缓存
     */
    private <R, ID> void rebuildAsync(
            String keyPrefix, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        rebuildExecutor.submit(() -> {
            // 在重建线程内加锁，保证锁的持有与释放为同一线程；
            // 显式 lease=30s 禁用 watchdog，重建线程挂死时锁最迟 30s 自动释放
            boolean locked;
            try {
                locked = lock.tryLock(0, REBUILD_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 已经有别的线程在重建
            }
            if (!locked) {
                return; // 已经有别的线程在重建
            }
            try {
                R data = dbFallback.apply(id);
                if (data != null) {
                    writeWithLogicalExpire(keyPrefix + id, data, ttl, unit);
                }
                cacheMetrics.rebuilt(true);
            } catch (Exception e) {
                // 重建失败原本只会被线程池吞掉，落指标后才能配告警发现「缓存长期不刷新」
                cacheMetrics.rebuilt(false);
                log.error("[多级缓存] 异步重建失败: {}", keyPrefix + id, e);
            } finally {
                // 租期内未完成时锁已自动过期，此处再 unlock 会抛 IllegalMonitorStateException
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }

    /**
     * 写入 Redis，带逻辑过期时间戳
     */
    private void writeWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 随机 TTL ± 20%，避免同时过期引发雪崩
        long baseSec = unit.toSeconds(ttl);
        long jitter = (long) (baseSec * 0.2 * RANDOM.nextDouble());
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(baseSec + jitter));
        // 物理 TTL 保险丝：不参与正常过期判断（那是 expireTime 的职责）。逻辑过期把过期判断
        // 挪进了应用层，Redis 侧这个 key 本身永不过期；一旦主动删除与异步重建同时失败，
        // 脏数据将无限期驻留。这里保证最迟 3 倍逻辑 TTL 内自愈，把 ∞ 变成有限值。
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData),
                baseSec * 3, TimeUnit.SECONDS);
    }
}
