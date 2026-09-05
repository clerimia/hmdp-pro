package com.hmdp.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baidu.fsg.uid.UidGenerator;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.RocketMQProducer;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 秒杀对账任务：兜底 MQ 消息丢失与库存漂移。
 * 订单表是唯一账本，库存是派生值。每轮按 ①关单 → ②补单 → ③库存重算 执行，
 * 账本修复（①②）必须先于库存重算（③）。只处理结束后 7 天内的券：窗口外不再兜底，
 * 活动 key 出窗后续期 14d 后自然过期，历史 key 总量有界。
 */
@Slf4j
@Component
public class SeckillReconcileTask {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private RocketMQProducer rocketMQProducer;
    @Resource
    private UidGenerator uidGenerator;

    /** 超时关单阈值：与延迟消息档位一致（15 分钟） */
    private static final int ORDER_TIMEOUT_MINUTES = 15;
    /** 秒杀结束多久后允许补单/重算（等消费重试链排空，重试 cadence 最长约 6 分钟） */
    private static final int RECONCILE_AFTER_END_MINUTES = 2;
    /** 对账窗口（天）：无下界会每轮全量扫历史券，成本随历史无限增长 */
    private static final int RECONCILE_WINDOW_DAYS = 7;
    /**
     * 活动结束后的 key 存活期（秒，14d）：窗口内的券每轮续到该值，出窗后自然死亡。
     * 续期必须放在改写之后且不可被「已一致」分支跳过——裸 SET 不带 EX 会清掉 TTL。
     */
    private static final long KEY_TTL_AFTER_END_SECONDS = 14 * 86400L;
    /** 对账分布式锁租期（秒）：小于调度间隔 60s，实例宕机后锁快速自动释放 */
    private static final long RECONCILE_LOCK_LEASE_SECONDS = 50;
    /** 关单扫描批大小：只查 id + LIMIT 分批，防 broker 丢光关单消息的极端场景下全量拉取 OOM */
    private static final int CLOSE_SCAN_BATCH_SIZE = 1000;

    /**
     * 每分钟执行。多实例用分布式锁保证单实例执行；任务幂等，超租期的并发无副作用。
     * 各步骤独立捕获异常：一个兜底失败不该连带废掉其他兜底。
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcile() {
        RLock lock = redissonClient.getLock("lock:seckill:reconcile");
        boolean locked;
        try {
            // wait=0：他人对账中就立即放弃；lease=50s：宕机后锁快速自释放，避免 watchdog 无限续期
            locked = lock.tryLock(0, RECONCILE_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!locked) {
            return;
        }
        try {
            runStep("关单", this::closeTimeoutOrders);
            Boolean supplemented = runStep("补单", this::supplementMissingOrders);
            if (Boolean.TRUE.equals(supplemented)) {
                // 补单是异步消息，本轮重算会读到「补的单还没落库」的账本，把库存算大；
                // 跳过一轮等账本稳定，晚算没有代价，算错才有
                log.warn("对账：本轮发生补单，跳过库存重算，下轮再算");
            } else {
                runStep("库存重算", this::reconcileFinishedStocks);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 执行单个对账步骤并独立捕获异常，失败返回 null 由调用方降级 */
    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (Exception e) {
            log.error("对账步骤异常: " + name, e);
        }
    }

    private <T> T runStep(String name, Supplier<T> step) {
        try {
            return step.get();
        } catch (Exception e) {
            log.error("对账步骤异常: " + name, e);
            return null;
        }
    }

    /**
     * ① 关单兜底：按 createTime 扫描超时未支付订单，直接关单（不依赖 MQ）。
     * 覆盖关单延迟消息的一切丢失场景（发送失败、broker 丢失、消费失败进 DLQ），
     * 延迟与延迟消息同分布（15~16 分钟），cancelTimeoutOrder 幂等，二者并发安全。
     *
     * <p>分批拉取：broker 丢光关单消息的极端场景下未支付单可达海量（如百万库存售罄），
     * 一次 .list() 全量拉实体必然 OOM。每批 CAS 关单后行自动离开谓词（status 变 4），
     * 无需偏移量，同谓词重查即自然收敛。
     */
    private void closeTimeoutOrders() {
        int scanned = 0;
        List<VoucherOrder> batch;
        do {
            batch = voucherOrderService.lambdaQuery()
                    .select(VoucherOrder::getId)
                    .eq(VoucherOrder::getStatus, RedisConstants.ORDER_STATUS_UNPAID)
                    .lt(VoucherOrder::getCreateTime, LocalDateTime.now().minusMinutes(ORDER_TIMEOUT_MINUTES))
                    .last("LIMIT " + CLOSE_SCAN_BATCH_SIZE)
                    .list();
            for (VoucherOrder order : batch) {
                voucherOrderService.cancelTimeoutOrder(order.getId());
            }
            scanned += batch.size();
        } while (batch.size() == CLOSE_SCAN_BATCH_SIZE);
        if (scanned > 0) {
            log.warn("对账关单兜底：本轮处理 {} 笔超时未支付订单", scanned);
        }
    }

    /**
     * ② 补单：兜 CREATE 消息丢失。seckill:order 集合（Lua 已扣库存的用户）与
     * 订单表已落库用户的差集 = 丢单，补发 CREATE 消息。
     *
     * <p>补单复用入口返回给用户的原 orderId（seckill:claim 认领映射）——换新号补单
     * 会让用户轮询旧单号永远查不到。复用原号后消息体与迟到原消息一致，消费者靠
     * 主键幂等天然去重；claim 缺失（存量数据/超长静默活动）回退新号并告警。
     *
     * @return 本轮是否补发过订单。true 时调用方跳过本轮库存重算
     */
    private boolean supplementMissingOrders() {
        boolean supplemented = false;
        LocalDateTime now = LocalDateTime.now();
        List<SeckillVoucher> vouchers = seckillVoucherService.lambdaQuery()
                .ge(SeckillVoucher::getEndTime, now.minusDays(RECONCILE_WINDOW_DAYS))
                .lt(SeckillVoucher::getEndTime, now.minusMinutes(RECONCILE_AFTER_END_MINUTES))
                .list();
        for (SeckillVoucher voucher : vouchers) {
            Long voucherId = voucher.getVoucherId();
            // 早期退出：一人一单下订单用户 ⊆ seckill:order 集合（每个订单都源自 Lua 成功→sadd，
            // 取消的用户也留在集合里），且集合在活动结束后冻结。因此
            // COUNT(订单) == SCARD(集合) ⟺ 无丢单（子集 + 基数相等 = 集合相等）。
            // 收敛后的券每轮只花 1 次 SCARD(O(1)) + 1 次索引 COUNT，不做 SMEMBERS 与全量拉单——
            // 热门券十万成员时这两步才是每分钟一次的大头。EARLY 模式不写集合，SCARD=0 直接跳过。
            // 已知边界：活动中途切换测试档位（FULL→EARLY）会产生不在集合里的订单，基数相等
            // 但仍有丢单——测试行为，本就属对账 best-effort 范畴。
            Long scard = stringRedisTemplate.opsForSet()
                    .size(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            if (scard == null || scard == 0) {
                continue;
            }
            long orderCount = voucherOrderService.lambdaQuery()
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .count();
            if (orderCount == scard) {
                continue;
            }
            Set<String> claimed = stringRedisTemplate.opsForSet()
                    .members(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            List<VoucherOrder> created = voucherOrderService.lambdaQuery()
                    .select(VoucherOrder::getUserId)
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .list();
            Set<String> createdSet = created.stream()
                    .map(o -> String.valueOf(o.getUserId()))
                    .collect(Collectors.toSet());

            for (String userId : claimed) {
                if (createdSet.contains(userId)) {
                    continue;
                }
                String claimedOrderId = (String) stringRedisTemplate.opsForHash()
                        .get(RedisConstants.SECKILL_CLAIM_KEY + voucherId, userId);
                VoucherOrder order = new VoucherOrder();
                if (claimedOrderId != null) {
                    order.setId(Long.valueOf(claimedOrderId));
                } else {
                    order.setId(uidGenerator.getUID());
                    log.warn("对账补单：claim 映射缺失，回退新订单号, voucherId={}, userId={}", voucherId, userId);
                }
                order.setUserId(Long.valueOf(userId));
                order.setVoucherId(voucherId);
                try {
                    rocketMQProducer.sendOrderCreate(order);
                    supplemented = true;
                    log.warn("对账补单：voucherId={}, userId={}, orderId={}",
                            voucherId, userId, order.getId());
                } catch (Exception e) {
                    // 发送失败不抛异常，下轮再试；消费者幂等，重复补发安全
                    log.error("对账补单发送失败, voucherId={}, userId={}", voucherId, userId, e);
                }
            }
        }
        return supplemented;
    }

    /**
     * ③ 库存重算：expected = initial_stock − 有效订单数（1未支付/2已支付/3已核销），
     * Redis 与 DB 统一改写。预期值只来自订单表，不存在修错方向，每轮收敛。
     */
    private void reconcileFinishedStocks() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillVoucher> finished = seckillVoucherService.lambdaQuery()
                .ge(SeckillVoucher::getEndTime, now.minusDays(RECONCILE_WINDOW_DAYS))
                .lt(SeckillVoucher::getEndTime, now.minusMinutes(RECONCILE_AFTER_END_MINUTES))
                .list();
        for (SeckillVoucher voucher : finished) {
            Long voucherId = voucher.getVoucherId();
            int initial = voucher.getInitialStock() == null ? 0 : voucher.getInitialStock();
            if (initial <= 0) {
                // 只跳过重算不跳过续期：key 生命周期与账本无关，缺 initialStock 的券的 key 也要死亡
                log.warn("对账跳过重算：voucherId={} 缺少 initialStock", voucherId);
            } else {
                // 在途单守卫：活动刚结束时 15 分钟关单仍在发生，此时算出的 expected 下一轮就会被
                // 关单改写——写下瞬态值再自打脸没有意义。等全部订单终态（无 status=1）后一次算准。
                // 精确命中 idx_voucher_status 两列前缀，成本 O(在途单数)≈O(1)。
                long pending = voucherOrderService.lambdaQuery()
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .eq(VoucherOrder::getStatus, RedisConstants.ORDER_STATUS_UNPAID)
                        .count();
                if (pending == 0) {
                    long valid = voucherOrderService.lambdaQuery()
                            .eq(VoucherOrder::getVoucherId, voucherId)
                            .in(VoucherOrder::getStatus,
                                    RedisConstants.ORDER_STATUS_UNPAID, RedisConstants.ORDER_STATUS_PAID,
                                    RedisConstants.ORDER_STATUS_VERIFIED)
                            .count();
                    int expected = Math.max(0, initial - (int) valid);

                    boolean dbOk = voucher.getStock() != null && voucher.getStock() == expected;
                    String redisStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
                    boolean redisOk = String.valueOf(expected).equals(redisStock);
                    if (!dbOk || !redisOk) {
                        log.warn("对账库存重算：voucherId={}, db={}, redis={} → expected={}（初始={}, 有效订单={}）",
                                voucherId, voucher.getStock(), redisStock, expected, initial, valid);
                        stringRedisTemplate.opsForValue().set(
                                SECKILL_STOCK_KEY + voucherId, String.valueOf(expected));
                        seckillVoucherService.update(
                                Wrappers.<SeckillVoucher>lambdaUpdate()
                                        .set(SeckillVoucher::getStock, expected)
                                        .eq(SeckillVoucher::getVoucherId, voucherId));
                    }
                }
            }

            // 活动 key 统一续期（见 KEY_TTL_AFTER_END_SECONDS 注释；对不存在的 key 是 no-op）。
            // 必须无条件：被任何 skip 分支绕过都会让 key 重新永生（内存泄漏）
            stringRedisTemplate.expire(
                    SECKILL_STOCK_KEY + voucherId, KEY_TTL_AFTER_END_SECONDS, TimeUnit.SECONDS);
            stringRedisTemplate.expire(
                    RedisConstants.SECKILL_ORDER_KEY + voucherId, KEY_TTL_AFTER_END_SECONDS, TimeUnit.SECONDS);
            stringRedisTemplate.expire(
                    RedisConstants.SECKILL_CLAIM_KEY + voucherId, KEY_TTL_AFTER_END_SECONDS, TimeUnit.SECONDS);
        }
    }
}
