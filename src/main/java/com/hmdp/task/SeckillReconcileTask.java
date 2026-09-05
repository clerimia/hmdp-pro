package com.hmdp.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baidu.fsg.uid.UidGenerator;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.observability.ReconcileMetrics;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;

import static com.hmdp.utils.RedisConstants.SECKILL_STOCK_KEY;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 领券对账任务（秒杀技法链路的兜底）：兜 MQ 消息丢失与库存漂移。
 * 订单表是唯一账本，库存是派生值。每轮按 ①补单 → ②库存重算 执行，
 * 账本修复（①）必须先于库存重算（②）。只处理结束后 7 天内的券：窗口外不再兜底，
 * 活动 key 出窗后续期 14d 后自然过期，历史 key 总量有界。
 *
 * <p>补单不走 MQ：同步直调 {@link IVoucherOrderService#createOrderFromMQ}——
 * 与消费者同一落库路径（主键幂等 / dbBreaker / 排队状态回写全部继承），且不依赖
 * broker 可用：对账本就是 MQ 故障的兜底，补单再经 MQ 投递等于兜底反过来依赖
 * 被兜的对象。
 *
 * <p>运维撤回（删订单行）只在活动结束后开放：活动期间 seckill:order 集合里还有该用户，
 * ①补单会把删掉的行再补回来，DELETE 自愈不了；活动结束后 DELETE 无需手工改库存——
 * 删行使 expected = initial − COUNT(*) 自动变大，下轮 ② 会把多出的库存补回去。
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
    private UidGenerator uidGenerator;
    @Resource
    private ReconcileMetrics reconcileMetrics;

    /**
     * 活动结束多久后允许补单/重算：必须大于消费重试链总长，否则仍在重试中的单
     * 会被误判成丢单（补发动作安全但污染 supplement 口径，WARN 噪音失真）。
     * 推导：{@code OrderMQConsumer#MAX_RECONSUME_TIMES = 5}，RocketMQ 并发消费
     * 失败重投的档位 = 3 + reconsumeTimes，即默认档位表 L3~L7
     * （10s/30s/1m/2m/3m），累计 6m40s 后消息「要么落库要么进死信」，
     * 此刻差集判定才可信。取 8 = ceil(400s/60) + 1 分钟余量。
     * ⚠️ 推导依赖 broker 默认 messageDelayLevel 档位表——自定义档位表会静默
     * 改变重试节奏并让本守卫失效（只表现为补单 WARN 变多，极隐蔽），
     * 见 docker/rocketmq/broker.conf 的注释。
     */
    private static final int RECONCILE_AFTER_END_MINUTES = 8;
    /** 对账窗口（天）：无下界会每轮全量扫历史券，成本随历史无限增长 */
    private static final int RECONCILE_WINDOW_DAYS = 7;
    /**
     * 活动结束后的 key 存活期（秒，14d）：窗口内的券每轮续到该值，出窗后自然死亡。
     * 续期必须放在改写之后且不可被「已一致」分支跳过——裸 SET 不带 EX 会清掉 TTL。
     */
    private static final long KEY_TTL_AFTER_END_SECONDS = 14 * 86400L;
    /** 对账分布式锁租期（秒）：小于调度间隔 60s，实例宕机后锁快速自动释放 */
    private static final long RECONCILE_LOCK_LEASE_SECONDS = 50;
    /**
     * 单轮补单动作上限：补单是同步落库（每个动作一次 DB 事务），上限防止大积压
     * 把单轮执行拖到分钟级、占住调度线程；剩余差集下轮（60s 后）继续，收敛只是延迟。
     */
    private static final int SUPPLEMENT_LIMIT_PER_ROUND = 100;
    /** SSCAN 每次网络往返的 COUNT 提示（Redis 侧的批量提示，非精确批量大小） */
    private static final int SSCAN_COUNT_HINT = 500;

    // —— 指标 tag 合法取值（口径契约见 ReconcileMetrics 类注释）——
    private static final String OUTCOME_COMPLETED = "completed";
    private static final String OUTCOME_SKIPPED_SUPPLEMENT = "skipped_supplement";
    private static final String OUTCOME_SKIPPED_LOCK = "skipped_lock";
    private static final String STEP_SUPPLEMENT = "supplement";
    private static final String STEP_RESTOCK = "restock";

    /**
     * 补单步骤的三态结果。不用 Boolean 三态（TRUE/FALSE/null）——语义只能靠注释
     * 维系的东西就是隐患，枚举把语义写进类型。ERROR 态由 {@link #runStep} 捕获
     * 异常后以 null 表达，不进枚举：异常路径本来就不该出现在正常返回值的类型里。
     */
    private enum StepResult {
        /** 本轮发生过补单动作（含单笔失败）：账本可能仍在变化，本轮跳过库存重算 */
        SUPPLEMENTED,
        /** 无丢单，账本干净，可以进入库存重算 */
        CLEAN
    }

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
            // 这个分支曾经整个方法静默 return——多实例下「对账没跑」与「跑了但没事」
            // 完全无法区分。round{outcome=skipped_lock} 让它可观测、可断言。
            reconcileMetrics.round(OUTCOME_SKIPPED_LOCK);
            return;
        }
        try {
            StepResult supplemented = runStep(STEP_SUPPLEMENT, this::supplementMissingOrders);
            if (supplemented == StepResult.CLEAN) {
                runStep(STEP_RESTOCK, () -> {
                    reconcileFinishedStocks();
                    return StepResult.CLEAN;
                });
                reconcileMetrics.round(OUTCOME_COMPLETED);
            } else {
                // 补过单（SUPPLEMENTED）或异常（null）都跳过重算：补单结果不确定就跳过——
                // 补单半途中断时 COUNT 比真实账本少几笔，expected 会算大、凭空多放库存。
                // 晚算没有代价，算错才有。
                log.warn("对账：本轮补单结果不确定（{}），跳过库存重算，下轮再算",
                        supplemented == StepResult.SUPPLEMENTED ? "发生过补单" : "步骤异常");
                reconcileMetrics.round(OUTCOME_SKIPPED_SUPPLEMENT);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 执行单个对账步骤并独立捕获异常，失败返回 null 由调用方降级；
     * 成败计入 step{step,result}——被吞掉的异常不能只有 log.error 一个出口。
     */
    private <T> T runStep(String step, Supplier<T> action) {
        try {
            T result = action.get();
            reconcileMetrics.step(step, true);
            return result;
        } catch (Exception e) {
            log.error("对账步骤异常: {}", step, e);
            reconcileMetrics.step(step, false);
            return null;
        }
    }

    /**
     * ① 补单：兜 CREATE 消息丢失。seckill:order 集合（Lua 已扣库存的用户）与
     * 订单表已落库用户的差集 = 丢单，逐个同步补落库。
     *
     * <p><b>丢单的后果不是「少卖一份」</b>，而是该用户被 seckill:order 集合永久锁死
     * 却没拿到券（Lua 已 sadd 且从不 srem，之后每次领取都被 sismember 拒绝）——
     * 补单是本系统唯一能把它修回来的路径，也是 ② 重算不超卖的前提
     * （expected = initial − COUNT，COUNT 必须追上 SCARD，否则凭空多放库存）。
     *
     * <p>补单复用入口返回给用户的原 orderId（seckill:claim 认领映射）——换新号补单
     * 会让用户轮询旧单号永远查不到。复用原号后与迟到的原消息完全一致，落库靠主键
     * 幂等天然去重；claim 缺失（存量数据/超长静默活动）回退新号并告警。
     *
     * <p>遍历用 SSCAN 游标代替 SMEMBERS——单条命令全量拉成员，热门券十万成员时
     * 会阻塞 Redis 单线程；claim 映射 HMGET 批量取，不逐个 HGET；单轮补单动作有
     * 上限（同步落库 = 每动作一次 DB 事务），剩余差集下轮继续。SSCAN 在完整游标
     * 周期内可能重复返回同一成员，用 attempted 集合去重，防止同一轮重复补。
     *
     * @return SUPPLEMENTED=本轮发生过补单动作（含单笔失败）；CLEAN=无丢单
     */
    private StepResult supplementMissingOrders() {
        boolean supplemented = false;
        int budget = SUPPLEMENT_LIMIT_PER_ROUND;
        LocalDateTime now = LocalDateTime.now();
        List<SeckillVoucher> vouchers = seckillVoucherService.lambdaQuery()
                .ge(SeckillVoucher::getEndTime, now.minusDays(RECONCILE_WINDOW_DAYS))
                .lt(SeckillVoucher::getEndTime, now.minusMinutes(RECONCILE_AFTER_END_MINUTES))
                .list();
        for (SeckillVoucher voucher : vouchers) {
            if (budget <= 0) {
                break;
            }
            Long voucherId = voucher.getVoucherId();
            // 早期退出：一人一单下订单用户 ⊆ seckill:order 集合（每个订单都源自 Lua 成功→sadd，
            // 取消的用户也留在集合里），且集合在活动结束后冻结。因此
            // COUNT(订单) == SCARD(集合) ⟺ 无丢单（子集 + 基数相等 = 集合相等）。
            // 收敛后的券每轮只花 1 次 SCARD(O(1)) + 1 次索引 COUNT，不做 SSCAN 与全量拉单——
            // 热门券十万成员时这两步才是每分钟一次的大头。EARLY 模式不写集合，SCARD=0 直接跳过。
            Long scard = stringRedisTemplate.opsForSet()
                    .size(RedisConstants.SECKILL_ORDER_KEY + voucherId);
            if (scard == null || scard == 0) {
                continue;
            }
            long claimedCount = voucherOrderService.lambdaQuery()
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .count();
            if (claimedCount == scard) {
                continue;
            }
            if (claimedCount > scard) {
                // 订单用户 ⊆ 集合（一人一单），COUNT > SCARD 只可能是测试档位中途切换
                // （FULL→EARLY 产生了不在集合里的订单）或集合被清理。差集里没有任何 userId，
                // 补单对这种情况无能为力，不做无谓遍历。误差方向是安全的：
                // ② 会算出偏小的 expected → 少卖（丢收入），不是超卖。
                log.warn("对账：订单数({}) > 已扣库存集合({})，疑似档位切换或集合被清理，voucherId={}",
                        claimedCount, scard, voucherId);
                continue;
            }
            Set<String> createdSet = voucherOrderService.lambdaQuery()
                    .select(VoucherOrder::getUserId)
                    .eq(VoucherOrder::getVoucherId, voucherId)
                    .list().stream()
                    .map(o -> String.valueOf(o.getUserId()))
                    .collect(Collectors.toSet());

            Set<String> attempted = new HashSet<>();
            List<String> missing = new ArrayList<>();
            try (Cursor<String> cursor = stringRedisTemplate.opsForSet().scan(
                    RedisConstants.SECKILL_ORDER_KEY + voucherId,
                    ScanOptions.scanOptions().count(SSCAN_COUNT_HINT).build())) {
                while (cursor.hasNext() && budget > 0) {
                    String userId = cursor.next();
                    if (createdSet.contains(userId) || !attempted.add(userId)) {
                        continue;
                    }
                    missing.add(userId);
                    budget--;
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            // HMGET 一次取回本批差集的 claim 映射，代替逐个 HGET
            List<Object> claims = stringRedisTemplate.opsForHash()
                    .multiGet(RedisConstants.SECKILL_CLAIM_KEY + voucherId, new ArrayList<Object>(missing));
            for (int i = 0; i < missing.size(); i++) {
                String userId = missing.get(i);
                VoucherOrder order = new VoucherOrder();
                Object claimedOrderId = claims.get(i);
                if (claimedOrderId != null) {
                    order.setId(Long.valueOf(claimedOrderId.toString()));
                } else {
                    order.setId(uidGenerator.getUID());
                    log.warn("对账补单：claim 映射缺失，回退新订单号, voucherId={}, userId={}",
                            voucherId, userId);
                }
                order.setUserId(Long.valueOf(userId));
                order.setVoucherId(voucherId);
                try {
                    // 同步直调消费者同款落库路径：主键幂等 / dbBreaker / 排队状态回写
                    // 全部继承，不绕过消费者里的任何防线
                    voucherOrderService.createOrderFromMQ(order);
                    reconcileMetrics.supplement(true);
                    supplemented = true;
                    log.warn("对账补单：voucherId={}, userId={}, orderId={}",
                            voucherId, userId, order.getId());
                } catch (Exception e) {
                    // 单笔失败不中断本轮其余补单；下轮差集重算时它仍在差集里，
                    // 主键幂等保证重试安全
                    reconcileMetrics.supplement(false);
                    supplemented = true;
                    log.error("对账补单落库失败, voucherId={}, userId={}", voucherId, userId, e);
                }
            }
        }
        return supplemented ? StepResult.SUPPLEMENTED : StepResult.CLEAN;
    }

    /**
     * ② 库存重算：expected = initial_stock − COUNT(*)，Redis 与 DB 统一改写。
     * 预期值只来自订单表，不存在修错方向，每轮收敛。
     *
     * <p><b>COUNT(*) 不筛 used 不是省事，是语义要求</b>：initial_stock 是发放库存
     * （能领多少张），不是使用库存。券被领走的那一刻就永久占掉一个名额，核销与否
     * 不影响库存——used=0 与 used=1 在库存口径上完全等价。若将来有人把它「优化」成
     * COUNT(WHERE used=0)，已核销的券会被剔出账本，凭空多放一批库存，直接超卖。
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
                // 只跳过重算不跳过续期：key 生命周期与账本无关，缺 initialStock 的券的 key 也要死亡。
                // 数据缺陷不是判定结果，不计 restock（口径见 ReconcileMetrics）
                log.warn("对账跳过重算：voucherId={} 缺少 initialStock", voucherId);
            } else {
                long claimed = voucherOrderService.lambdaQuery()
                        .eq(VoucherOrder::getVoucherId, voucherId)
                        .count();
                int expected = Math.max(0, initial - (int) claimed);

                boolean dbOk = voucher.getStock() != null && voucher.getStock() == expected;
                String redisStock = stringRedisTemplate.opsForValue().get(SECKILL_STOCK_KEY + voucherId);
                boolean redisOk = String.valueOf(expected).equals(redisStock);
                if (!dbOk || !redisOk) {
                    log.warn("对账库存重算：voucherId={}, db={}, redis={} → expected={}（初始={}, 已领取={}）",
                            voucherId, voucher.getStock(), redisStock, expected, initial, claimed);
                    reconcileMetrics.restock(true);
                    stringRedisTemplate.opsForValue().set(
                            SECKILL_STOCK_KEY + voucherId, String.valueOf(expected));
                    seckillVoucherService.update(
                            Wrappers.<SeckillVoucher>lambdaUpdate()
                                    .set(SeckillVoucher::getStock, expected)
                                    .eq(SeckillVoucher::getVoucherId, voucherId));
                } else {
                    // 每轮每张在窗券恰好 +1（adjusted 或 converged 二选一），漂移是否发生过
                    // 由 increase(restock{result="adjusted"}) 告警，与死信告警同构
                    reconcileMetrics.restock(false);
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
