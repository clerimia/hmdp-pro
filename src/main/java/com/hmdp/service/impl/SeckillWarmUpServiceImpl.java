package com.hmdp.service.impl;

import com.hmdp.dto.SeckillWindow;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.ISeckillWarmUpService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOCK_SECKILL_WARM_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_META_KEY;
import static com.hmdp.utils.RedisConstants.SECKILL_META_TTL_HOURS;
import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

/**
 * 秒杀预热：把 tb_seckill_voucher 的活动窗口与库存搬进 Redis。
 *
 * <p><b>最关键的约束——库存回填只在活动开始前安全。</b>
 * 活动开始后，Redis 里的库存是唯一真相：入口 Lua 已扣减、但订单还在 MQ 里排队没落库，
 * 此时 DB 的 {@code stock} 是「落后于 Redis 的最终账本」。若拿 DB 值回填 Redis，
 * 等于把已扣掉的库存又加回去 —— 直接超卖。所以这里宁可让领券失败，也绝不回填。
 */
@Slf4j
@Service
public class SeckillWarmUpServiceImpl implements ISeckillWarmUpService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    /** 预热锁租期（秒）：显式 lease 禁用 watchdog，覆盖一次 DB 查询 + 两次 Redis 写 */
    private static final long WARM_LOCK_LEASE_SECONDS = 10;

    /** 「券不存在」空值标记 TTL（分钟）：防止刷不存在的券穿透到 DB */
    private static final long NULL_MARK_TTL_MINUTES = 2;

    private static final String FIELD_BEGIN = "begin";
    private static final String FIELD_END = "end";
    private static final String FIELD_NONE = "none";

    @Override
    public SeckillWindow ensureWarmed(Long voucherId) {
        String metaKey = SECKILL_META_KEY + voucherId;
        SeckillWindow cached = readWindow(metaKey);
        if (cached != null) {
            return cached;
        }
        warmUp(voucherId);
        // 预热失败（Redis 故障等）时这里仍是 null，调用方按「券不可用」处理
        return readWindow(metaKey);
    }

    /** 读活动窗口；null = 未预热，或命中「券不存在」的空值标记 */
    private SeckillWindow readWindow(String metaKey) {
        Map<Object, Object> meta = stringRedisTemplate.opsForHash().entries(metaKey);
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        if ("1".equals(String.valueOf(meta.get(FIELD_NONE)))) {
            return null;
        }
        Object begin = meta.get(FIELD_BEGIN);
        Object end = meta.get(FIELD_END);
        if (begin == null || end == null) {
            // 半写的脏数据（写入中途中断），按未预热处理，交给 warmUp 整体覆盖
            return null;
        }
        return new SeckillWindow(Long.parseLong(begin.toString()), Long.parseLong(end.toString()));
    }

    private void warmUp(Long voucherId) {
        RLock lock = redissonClient.getLock(LOCK_SECKILL_WARM_KEY + voucherId);
        boolean locked;
        try {
            // wait=0：拿不到锁说明别的线程正在预热，本次请求直接放行到业务，
            // 下一轮再读即可；预热不是强依赖，不需要排队等锁。
            locked = lock.tryLock(0, WARM_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            String metaKey = SECKILL_META_KEY + voucherId;
            if (readWindow(metaKey) != null) {
                return; // 双重检查
            }
            SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
            if (voucher == null) {
                stringRedisTemplate.opsForHash().put(metaKey, FIELD_NONE, "1");
                stringRedisTemplate.expire(metaKey, NULL_MARK_TTL_MINUTES, TimeUnit.MINUTES);
                return;
            }
            Map<String, String> meta = new HashMap<>(4);
            meta.put(FIELD_BEGIN, String.valueOf(toMillis(voucher.getBeginTime())));
            meta.put(FIELD_END, String.valueOf(toMillis(voucher.getEndTime())));
            stringRedisTemplate.opsForHash().putAll(metaKey, meta);
            stringRedisTemplate.expire(metaKey, SECKILL_META_TTL_HOURS, TimeUnit.HOURS);

            warmUpStock(voucherId, voucher);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 库存回填：仅当 key 不存在、且活动尚未开始时才写。
     *
     * <p>两个条件缺一不可——key 不存在是幂等前提（避免覆盖进行中的库存），
     * 活动未开始是正确性前提（见类注释）。
     */
    private void warmUpStock(Long voucherId, SeckillVoucher voucher) {
        String stockKey = SECKILL_STOCK_KEY + voucherId;
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(stockKey))) {
            return;
        }
        long beginMillis = toMillis(voucher.getBeginTime());
        if (beginMillis <= System.currentTimeMillis()) {
            // 活动已开始却没库存：通常是 Redis 数据丢失或被淘汰。
            // 此时只能 fail-closed（Lua 判 stock==nil 返回库存不足），
            // 由对账任务的库存重算在活动结束后修正。
            log.error("活动已开始但 Redis 库存缺失，拒绝从 DB 回填以避免超卖, voucherId={}", voucherId);
            return;
        }
        Boolean written = stringRedisTemplate.opsForValue()
                .setIfAbsent(stockKey, String.valueOf(voucher.getStock()));
        if (Boolean.TRUE.equals(written)) {
            log.info("秒杀库存预热完成, voucherId={}, stock={}", voucherId, voucher.getStock());
        }
    }

    private long toMillis(LocalDateTime time) {
        return time == null ? 0L : time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
