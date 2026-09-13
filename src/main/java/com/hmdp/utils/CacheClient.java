package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.config.ObservabilityConfig;
import com.hmdp.observability.CacheMetrics;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.CACHE_NULL_TTL;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    /**
     * 重建线程池。用容器里的 {@code traceAwareExecutor} 而不是自建线程池：
     * 它挂了 {@link com.hmdp.observability.MdcTaskDecorator}，
     * 重建任务的日志才能带上发起请求的 traceId，否则异步重建这段在日志里是断的。
     */
    private final AsyncTaskExecutor rebuildExecutor;

    private final CacheMetrics cacheMetrics;

    /** 缓存重建互斥锁租期（秒）：显式 lease 禁用 watchdog，线程挂死时锁最迟 30s 自动释放 */
    private static final long REBUILD_LOCK_LEASE_SECONDS = 30;
    /** 缓存击穿互斥锁租期（秒）：DB 回源 + 写缓存的上界 */
    private static final long MUTEX_LOCK_LEASE_SECONDS = 30;

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       RedissonClient redissonClient,
                       @Qualifier(ObservabilityConfig.TRACE_AWARE_EXECUTOR) AsyncTaskExecutor rebuildExecutor,
                       CacheMetrics cacheMetrics) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.rebuildExecutor = rebuildExecutor;
        this.cacheMetrics = cacheMetrics;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit){
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        // 命中空值标记
        if (json != null) {
            return null;
        }

        R r = dbFallback.apply(id);
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 逻辑过期不写空值标记，miss 直接回源
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        if(expireTime.isAfter(LocalDateTime.now())) {
            return r;
        }
        // 已过期 → 异步重建，抢不到锁说明已有线程在重建，直接返回旧数据。
        // 显式 wait/lease：wait=0（抢不到立即让位）、lease=30s 硬上限。
        // 无参 tryLock 会启用 watchdog 无限续期——重建线程挂死时锁永远不释放
        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        rebuildExecutor.submit(() -> {
            boolean locked;
            try {
                locked = lock.tryLock(0, REBUILD_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!locked) {
                return;
            }
            try {
                R newR = dbFallback.apply(id);
                this.setWithLogicalExpire(key, newR, time, unit);
                cacheMetrics.rebuilt(true);
            } catch (Exception e) {
                // 重建失败原本会被线程池静默吞掉，这里落一个 error 指标，便于配置告警
                cacheMetrics.rebuilt(false);
                log.error("缓存重建失败, key={}", key, e);
            } finally {
                // 租期内未完成时锁已自动过期，此处再 unlock 会抛 IllegalMonitorStateException
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
        return r;
    }

    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(shopJson)) {
            return JSONUtil.toBean(shopJson, type);
        }
        if (shopJson != null) {
            return null;
        }

        RLock lock = redissonClient.getLock(LOCK_SHOP_KEY + id);
        R r = null;
        try {
            // wait=0（拿不到走下面的休眠重试）、lease=30s 硬上限（不含 watchdog）
            boolean isLock = lock.tryLock(0, MUTEX_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            // 仅当前线程持有时释放，避免 IllegalMonitorStateException
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return r;
    }
}
