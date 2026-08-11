package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private Cache<String, Object> shopLocalCache;

    /** 逻辑过期异步重建线程池 */
    private static final ExecutorService REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

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

            if (expireTime.isAfter(LocalDateTime.now())) {
                // 未过期 → 写回 Caffeine，返回
                shopLocalCache.put(cacheKey, data);
                log.debug("[多级缓存] L2 Redis 命中: {}", cacheKey);
                return data;
            }

            // 逻辑过期 → 异步重建
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
        String lockKey = LOCK_SHOP_KEY + id;

        try {
            // 获取互斥锁
            boolean locked = tryLock(lockKey);
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
            unlock(LOCK_SHOP_KEY + id);
        }
    }

    /**
     * 异步重建过期缓存
     */
    private <R, ID> void rebuildAsync(
            String keyPrefix, ID id, Function<ID, R> dbFallback, Long ttl, TimeUnit unit) {

        String lockKey = LOCK_SHOP_KEY + id;
        boolean locked = tryLock(lockKey);
        if (!locked) return; // 已经有别的线程在重建

        REBUILD_EXECUTOR.submit(() -> {
            try {
                R data = dbFallback.apply(id);
                if (data != null) {
                    writeWithLogicalExpire(keyPrefix + id, data, ttl, unit);
                }
            } finally {
                unlock(lockKey);
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

    private boolean tryLock(String key) {
        Boolean ok = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(ok);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
