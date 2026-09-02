package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import com.hmdp.config.ObservabilityConfig;
import com.hmdp.observability.CacheMetrics;
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

    // ==================== 公开 API ====================

    /**
     * 多级缓存查询（穿透保护 + 逻辑过期防击穿）
     *
     * @param keyPrefix Redis key 前缀
     * @param id        业务 ID
     * @param type      返回类型
     * @param dbFallback 查库函数
     * @param ttl       缓存时间
     * @param unit      时间单位
     */
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
     * SETNX 互斥锁查库
     */
    private <R, ID> R queryWithMutexLock(
            String keyPrefix, ID id, Class<R> type,
            Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String key = keyPrefix + id;
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);

        try {
            // 获取互斥锁
            boolean locked = lock.tryLock();
            if (!locked) {
                // 拿不到锁 → 短暂休眠后递归重试
                Thread.sleep(50);
                return queryWithMultiLevel(keyPrefix, id, type, dbFallback, ttl, unit);
            }

            // 双重检查：获取锁后再次查 Redis
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                return JSONUtil.toBean((cn.hutool.json.JSONObject) redisData.getData(), type);
            }

            // 查 DB
            R result = dbFallback.apply(id);
            if (result == null) {
                // 空值缓存防穿透，加随机 TTL
                long randomTtl = CACHE_NULL_TTL + RANDOM.nextInt(3);
                stringRedisTemplate.opsForValue().set(key, "",
                        randomTtl, TimeUnit.MINUTES);
                return null;
            }

            // 写入 Redis（逻辑过期模式）
            writeWithLogicalExpire(key, result, ttl, unit);
            return result;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return dbFallback.apply(id);
        } finally {
            // 仅当前线程持有时释放，避免递归重试路径误删他人持有的锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 异步重建过期缓存
     */
    private <R, ID> void rebuildAsync(
            String keyPrefix, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        rebuildExecutor.submit(() -> {
            // 在重建线程内加锁，保证锁的持有与释放为同一线程
            if (!lock.tryLock()) {
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
                lock.unlock();
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
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
}
