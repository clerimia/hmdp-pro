package com.hmdp.service;

import com.hmdp.dto.SeckillWindow;

/**
 * 秒杀券预热：保证 Redis 里存在该券的「活动窗口」与「库存」。
 *
 * <p>为什么需要它：库存与活动信息原本只在 {@code addSeckillVoucher} 时写进 Redis，
 * 直接向 DB 插数据（种子数据、运营后台改单）不会产生 Redis key —— 结果是 Lua 里
 * {@code stock == nil} 走「库存不足」分支，秒杀永远失败，且没有任何报错。
 *
 * <p>入口每次调用只多一次 HGETALL（预热完成后），回源只在 key 缺失时发生，
 * 且用分布式锁收敛成一个线程。
 */
public interface ISeckillWarmUpService {

    /**
     * 确保该券已预热，并返回活动窗口。
     *
     * @return 活动窗口；{@code null} 表示该券不存在、或不是秒杀券
     */
    SeckillWindow ensureWarmed(Long voucherId);
}
