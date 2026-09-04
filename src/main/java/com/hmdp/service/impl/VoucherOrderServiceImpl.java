package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baidu.fsg.uid.UidGenerator;
import com.hmdp.dto.Result;
import com.hmdp.dto.SeckillWindow;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.ErrorCode;
import com.hmdp.exception.SystemException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.RocketMQProducer;
import com.hmdp.mq.SeckillTxContext;
import com.hmdp.observability.ObservabilityRecorder;
import com.hmdp.observability.SeckillMetrics;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.ISeckillWarmUpService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillMode;
import com.hmdp.utils.UserHolder;
// 注意：注解 @Bulkhead 与核心类 io.github.resilience4j.bulkhead.Bulkhead 同名。
// 这里只 import 注解，type 直接用注解自带的 Bulkhead.Type 枚举（SEMAPHORE）。
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
// 注意：io.github.resilience4j.circuitbreaker.CircuitBreaker（核心类）与
// @CircuitBreaker 注解同名，同文件里只 import 注解，核心类用全限定名引用（见 dbDegraded）
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * <p><b>秒杀只有一条写路径</b>（原方案 B 已删除，理由见 {@link SeckillMode}）：
 * 入口 Lua 原子预扣 Redis 库存 + 一人一单 → 事务消息 → 消费者落库。
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    /** 活动窗口校验 + 库存预热的唯一入口 */
    @Resource
    private ISeckillWarmUpService seckillWarmUpService;

    @Resource
    private UidGenerator uidGenerator;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RocketMQProducer rocketMQProducer;
    @Resource
    private SeckillMetrics seckillMetrics;
    /** 落库降级语义用：读取 dbBreaker 实时状态，判断「订单还要多久才能落库」 */
    @Resource
    private CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * 落库事务模板。用模板而不是 {@code @Transactional} 注解，是因为本方法还要在
     * 事务提交之后发 MQ 延迟消息——注解只能包住整个方法，会把「发消息」也卷进事务，
     * 而 MQ 不是事务资源，回滚时消息撤不回来。
     */
    @Resource
    private TransactionTemplate transactionTemplate;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    /** 对比测试：只扣库存、不做 Lua 一人一单（EARLY 档位） */
    private static final DefaultRedisScript<Long> SECKILL_STOCK_ONLY_SCRIPT;

    /**
     * Redis 开关：seckill:test:protection = FULL|LEGACY|EARLY（默认 FULL）
     * <ul>
     *   <li>{@code FULL}：入口 Lua 扣库存 + sismember 一人一单，消费端靠主键幂等（默认）</li>
     *   <li>{@code LEGACY}：消费端额外加 Redisson 锁 + count 预查（保留为对照/回滚档位）</li>
     *   <li>{@code EARLY}：入口换用只扣库存的 Lua，不做一人一单，制造并发窗口验证
     *       DB 唯一索引的兜底能力（该档位不写 seckill:order 集合，对账补单覆盖不到它）</li>
     * </ul>
     */
    public static final String ONE_ORDER_PROTECTION_KEY = "seckill:test:protection";

    /** LEGACY 档位的消费端锁租期（秒）：落库耗时上界，显式 lease 禁用 watchdog */
    private static final long ORDER_LOCK_LEASE_SECONDS = 10;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_STOCK_ONLY_SCRIPT = new DefaultRedisScript<>();
        SECKILL_STOCK_ONLY_SCRIPT.setLocation(new ClassPathResource("seckill-stock-only.lua"));
        SECKILL_STOCK_ONLY_SCRIPT.setResultType(Long.class);
    }

    private String oneOrderProtection() {
        String mode = stringRedisTemplate.opsForValue().get(ONE_ORDER_PROTECTION_KEY);
        return mode == null || mode.isEmpty() ? "FULL" : mode.trim().toUpperCase();
    }

    /**
     * 取消超时订单：仅当订单仍处于未支付状态时回补库存
     * 由 RocketMQ 延迟消息触发；通过状态乐观更新保证幂等
     */
    public void cancelTimeoutOrder(Long orderId) {
        VoucherOrder order = getById(orderId);
        if (order == null || order.getStatus() == null || order.getStatus() != ORDER_STATUS_UNPAID) {
            return;
        }

        // 状态置为已取消
        boolean updated = update()
                .set("status", ORDER_STATUS_CANCELLED)
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!updated) {
            // 并发下已支付，无需处理
            return;
        }

        // 回补 Redis 库存
        stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + order.getVoucherId());
        // 回补 DB 库存
        seckillVoucherService.update()
                .setSql("stock = stock + 1")
                .eq("voucher_id", order.getVoucherId())
                .update();
        log.info("订单 {} 超时未支付，已取消并回补库存，券 {}", orderId, order.getVoucherId());
    }

    @Override
    public Result payOrder(Long orderId) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail(ErrorCode.LOGIN_REQUIRED);
        }
        // 模拟支付：仅当订单未支付时改为已支付。
        //
        // user_id 必须作为更新条件，不能只按 orderId 更新：否则任何登录用户只要拿到
        // 别人的 orderId，就能把别人的订单改成「已支付」——典型的 IDOR 越权。
        // 订单号虽由 UidGenerator 生成不可枚举，但秒杀接口会把它返回给下单者、
        // 前端还会明文展示，一旦从日志/截图/录屏泄漏出去就够用了。
        // 把归属判断下沉为 SQL 条件而不是「先查再比对」，是为了让它和状态判断
        // 一起构成原子 CAS，不存在 check-then-act 的竞态窗口。
        boolean updated = update()
                .set("status", ORDER_STATUS_PAID)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("user_id", user.getId())
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!updated) {
            // 不区分「订单不存在 / 不是你的订单 / 状态不允许」——
            // 统一文案可以避免攻击者用错误信息的差异来枚举订单号是否存在。
            // 排查所需的区分信息记在日志里，不外泄给调用方。
            log.warn("支付未生效（订单不存在、非本人或状态不允许）, orderId={}, userId={}",
                    orderId, user.getId());
            return Result.fail("订单不存在或状态不允许支付");
        }
        // 延迟关单消息不可撤销，到点后 cancelTimeoutOrder 通过状态乐观更新自动跳过已支付订单，无需额外处理
        return Result.ok("支付成功");
    }

    /**
     * MQ 消费者异步落库入口：幂等创建订单。
     *
     * <p><b>幂等键是订单主键 orderId，不是 (userId, voucherId)。</b>
     * 消息重投时消息体是同一个 {@code VoucherOrder}（含 id），撞的是主键；
     * 而一人一单在入口 Lua 的 {@code sismember} 就已经拦掉了，消费者根本收不到
     * 「一人多单」的消息。因此落库顺序必须是<b>先 insert 再扣库存</b>——重投的消息
     * 在 insert 就被主键拦下、事务回滚，库存压根不会被扣；反过来先扣库存的话，
     * 重投会先扣一次再回补，白白多两次写和一段中间态。
     *
     * <p><b>已去掉原来的 Redisson 锁 + count 预查。</b>
     * 它们保护的是「一人一单」，而该约束已由入口 Lua 保证，消费端的锁纯属重复串行化：
     * 每单多 2 次 Redis 往返（加锁/解锁）+ 1 次 SELECT，且同一用户的订单全部排队，
     * {@code tryLock(2s)} 在挤压时会迅速占满业务线程池。{@code LEGACY} 档位可切回对照。
     *
     * <p>P2 容错：{@code dbBreaker}（50%/窗口 20/半开 15s）包裹整个落库流程。
     * 熔断打开后方法入口即抛 {@code CallNotPermittedException}，消费端 catch 后返回
     * RECONSUME_LATER 延迟重投——不碰 DB 也不碰 Redis，配合消息重试上限 + 死信 +
     * 对账补单形成完整兜底阶梯。
     *
     * <p>已知权衡：方法内的 Redisson 锁（LEGACY 档）失败也会计入 dbBreaker
     * （Redis 故障打开 DB 熔断器）。可接受——消费端任何一环挂掉都应快速重投而不是
     * 占着消费线程干等；误开窗口只有半开期 15s，消息重投天然覆盖。
     */
    @CircuitBreaker(name = "dbBreaker")
    public void createOrderFromMQ(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Long orderId = voucherOrder.getId();
        if (userId == null || voucherId == null || orderId == null) {
            log.error("订单消息数据不完整，丢弃: {}", voucherOrder);
            return;
        }
        boolean legacy = "LEGACY".equals(oneOrderProtection());

        RLock redisLock = null;
        boolean locked = false;
        if (legacy) {
            redisLock = redissonClient.getLock("lock:order:" + userId);
            try {
                locked = redisLock.tryLock(2, ORDER_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                locked = false;
            }
            if (!locked) {
                log.error("不允许重复下单！");
                return;
            }
        }

        try {
            if (legacy) {
                int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if (count > 0) {
                    log.error("不允许重复下单！");
                    return;
                }
            }

            // 事务内：insert（靠主键去重）→ 扣 DB 库存，两者同生共死。
            // 这样既消除了「扣了库存但 insert 前进程崩溃」造成的少卖，
            // 也让库存不足时刚 insert 的订单一并回滚，不留无库存支撑的脏单。
            Boolean stockOk;
            try {
                stockOk = transactionTemplate.execute(status -> {
                    voucherOrder.setStatus(ORDER_STATUS_UNPAID);
                    save(voucherOrder);
                    boolean ok = seckillVoucherService.update()
                            .setSql("stock = stock - 1")
                            .eq("voucher_id", voucherId).gt("stock", 0)
                            .update();
                    if (!ok) {
                        status.setRollbackOnly();
                    }
                    return ok;
                });
            } catch (DuplicateKeyException e) {
                // 同一 orderId 重投（或 EARLY 档下撞 uk_user_voucher）：事务已回滚、
                // DB 库存未扣，零副作用，不需要回补。
                // 注意 EARLY 档的语义差异：入口扣了两次 Redis 却只落一单，会多扣一次
                // Redis 库存——该档位本就是「故意不用 Redis 去重」的对照组，
                // 不额外补偿，由活动结束后的对账重算收敛。
                log.info("重复订单消息，唯一约束拦截, orderId={}, userId={}, voucherId={}",
                        orderId, userId, voucherId);
                writeQueueStatusSafe(orderId, SeckillMode.QUEUE_SUCCESS);
                return;
            }
            if (!Boolean.TRUE.equals(stockOk)) {
                log.error("库存不足！orderId={}", orderId);
                writeQueueStatusSafe(orderId, SeckillMode.QUEUE_FAIL_STOCK);
                return;
            }

            // 事务已提交才发延迟关单消息：MQ 不是事务资源，放进事务里的话
            // 回滚时消息撤不回来，会留下指向不存在订单的关单消息。
            try {
                rocketMQProducer.sendOrderTimeout(orderId);
            } catch (Exception e) {
                // 关单延迟消息发送失败：只记日志的话订单会永远停在「未支付」（无自动关单保障）。
                // 升级为：记入 Redis 重试集合 → 对账任务每轮重发（SeckillReconcileTask#retryTimeoutMessages）
                // → 重发后仍到不了点还有 closeTimeoutOrders 按 createTime 扫描关单兜底。
                // cancelTimeoutOrder 是 CAS 幂等（仅未支付才关），重复触发无副作用。
                seckillMetrics.orderTimeoutSendError();
                stringRedisTemplate.opsForSet().add(
                        SECKILL_TIMEOUT_RETRY_KEY, orderId.toString());
                log.error("超时关单延迟消息发送失败，已记入重试集合等待对账重发, orderId={}",
                        orderId, e);
            }
            writeQueueStatusSafe(orderId, SeckillMode.QUEUE_SUCCESS);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("MQ 异步落库失败，等待重试, orderId={}", orderId, e);
            throw new RuntimeException(e);
        } finally {
            // 租期内未完成时锁已自动过期，此处再 unlock 会抛 IllegalMonitorStateException
            if (locked && redisLock != null && redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    /**
     * 事务消息本地事务：Lua 库存 + 一人一单 + 写 seckill:txn 标记
     */
    @Override
    public long executeSeckillLocalTransaction(Long voucherId, Long userId, Long orderId) {
        String protection = oneOrderProtection();
        DefaultRedisScript<Long> script = "EARLY".equals(protection) ? SECKILL_STOCK_ONLY_SCRIPT : SECKILL_SCRIPT;
        Long result = stringRedisTemplate.execute(
                script,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        return result == null ? 1L : result;
    }

    @Override
    public boolean hasSeckillTxnMarker(Long orderId) {
        Boolean exists = stringRedisTemplate.hasKey(SECKILL_TXN_KEY + orderId);
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 秒杀入口（P1 容错）：
     * <ul>
     *   <li>{@code @Bulkhead(seckillBulkhead)}：信号量 100（Tomcat 200 线程的一半），
     *       maxWait=0 许可耗尽立即拒——排队只会让延迟不可控，不如快速失败。</li>
     *   <li>{@code @CircuitBreaker(redisBreaker)}：Redis 故障计入 redisBreaker，
     *       熔断打开后新请求不再触碰 Redis，直接 503「活动火爆，请稍后」。</li>
     * </ul>
     * 降级语义 = <b>fail-closed</b>：无论哪个层拒绝（舱壁满 / 熔断打开 / Redis 不可用），
     * 都只能拒绝下单，绝不能放行——不超卖证明唯一依赖 Redis Lua 的原子扣减，
     * Redis 挂了还放行 = 拆掉正确性屏障，直接超卖。
     * 两个异常（舱壁满 / 熔断打开）由全局异常处理器统一转成 503 + SYS_BUSY。
     */
    @Bulkhead(name = "seckillBulkhead", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "redisBreaker")
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 埋点：计时 + 结果。reason 贯穿所有分支，finally 统一落定，
        // 这样任何一个 return 路径都不会漏统计
        ObservabilityRecorder.Sample sample = seckillMetrics.startSeckill();
        SeckillMetrics.Reason reason = SeckillMetrics.Reason.SUCCESS;
        try {
            // ⓪ 活动窗口校验：预热 meta（同时顺带补库存），再判断未开始 / 已结束。
            //    这一步此前完全缺失——活动没开抢能抢、结束了还能抢。
            //    校验放在 Lua 之前而不是塞进 Lua，是为了让 A/B 之外的降级路径
            //    （熔断打开）也能被挡住，且失败原因能被 reason 指标区分。
            SeckillWindow window = seckillWarmUpService.ensureWarmed(voucherId);
            if (window == null) {
                reason = SeckillMetrics.Reason.VOUCHER_NOT_SECKILL;
                return Result.fail(ErrorCode.VOUCHER_NOT_SECKILL);
            }
            long now = System.currentTimeMillis();
            if (window.isBeforeStart(now)) {
                reason = SeckillMetrics.Reason.NOT_STARTED;
                return Result.fail(ErrorCode.SECKILL_NOT_STARTED);
            }
            if (window.isAfterEnd(now)) {
                reason = SeckillMetrics.Reason.ENDED;
                return Result.fail(ErrorCode.SECKILL_ENDED);
            }

            Long userId = UserHolder.getUser().getId();
            long orderId = uidGenerator.getUID();
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);

            SeckillTxContext ctx = new SeckillTxContext(order);
            try {
                SendResult sendResult = rocketMQProducer.sendOrderCreateInTransaction(order, ctx);
                if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                    // 非 OK（FLUSH_DISK_TIMEOUT / SLAVE_NOT_AVAILABLE 等）视同发送失败：
                    // 抛异常让 mqBreaker 学到这次失败，而不是静默当成功
                    throw new SystemException(ErrorCode.SYS_MQ_UNAVAILABLE,
                            ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
                }
            } catch (SystemException e) {
                reason = SeckillMetrics.Reason.MQ_SEND_ERROR;
                // 不上抛是有意的：seckillVoucher 外层套着 redisBreaker，MQ 故障的异常穿过去
                // 会被误记成 Redis 失败，把爆炸半径扩散到健康依赖。转成业务响应
                // （200 + code=5004「下单通道暂时不可用」），redisBreaker 一个失败都不多记。
                return Result.fail(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                log.error("事务消息发送异常, userId={}, voucherId={}", userId, voucherId, e);
                reason = SeckillMetrics.Reason.MQ_SEND_ERROR;
                return Result.fail(ErrorCode.SYS_MQ_UNAVAILABLE, ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
            }

            long r = ctx.getLuaResult();
            if (r == 0) {
                // 先落「处理中」状态：落库是异步的，前端凭 code 轮询 getSeckillResult 时
                // 会走 Redis 而不是每次都打到 DB——降级窗口里 DB 本就吃紧，
                // 让轮询再压上去就是把降级变成雪崩放大器。
                writeQueueStatusSafe(orderId, SeckillMode.QUEUE_WAITING);
                // 落库降级语义（P2 关键决策）：Lua 成功只代表 Redis 预扣成功，订单此刻尚未落库，
                // 要等消费者落库。dbBreaker 打开/半开 = 落库遥遥无期——这时返回"成功"是在撒谎，
                // 用户会看到「下单成功但订单消失」。说谎的降级比明确的报错更糟：
                // 诚实返回 ORDER_PROCESSING（业务码 1100），并把 orderId 放进 data
                // ——没有 orderId 前端就无从查询后续结果。
                if (dbDegraded()) {
                    reason = SeckillMetrics.Reason.DB_DEGRADED;
                    seckillMetrics.degraded("dbBreaker", SeckillMetrics.Reason.DB_DEGRADED);
                    return Result.fail(ErrorCode.ORDER_PROCESSING, orderId);
                }
                return Result.ok(orderId);
            }
            if (r == 1) {
                reason = SeckillMetrics.Reason.STOCK_OUT;
                return Result.fail(ErrorCode.STOCK_OUT);
            }
            if (r == 2) {
                reason = SeckillMetrics.Reason.REPEAT;
                return Result.fail(ErrorCode.ORDER_REPEAT);
            }
            // r == -1：事务监听器捕获了本地事务（Lua）的异常后置 UNKNOW 并把结果标成 -1。
            // 这里必须以 SystemException 上抛而不是返回 Result.fail：
            //   1. Result.fail 是"正常返回"，redisBreaker 一个失败都记不到，永远学不会熔断；
            //   2. SystemException 由全局处理器转成 503「缓存服务暂时不可用」= 语义化快速失败。
            reason = SeckillMetrics.Reason.SYSTEM_ERROR;
            throw new SystemException(ErrorCode.SYS_REDIS_UNAVAILABLE, "秒杀库存扣减暂不可用");
        } finally {
            seckillMetrics.finishSeckill(sample, SeckillMode.A, reason);
        }
    }

    @Override
    public Result getSeckillResult(Long orderId) {
        if (orderId == null) {
            return Result.fail("订单号不能为空");
        }
        Map<String, Object> data = new HashMap<>(4);
        data.put("orderId", orderId);

        String status = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_KEY + orderId);
        if (status != null) {
            data.put("status", status);
            return Result.ok(data);
        }
        // 无排队状态：状态 TTL 过期、或入口写入失败 → 查订单表兜底
        VoucherOrder order = getById(orderId);
        if (order != null) {
            data.put("status", SeckillMode.QUEUE_SUCCESS);
            data.put("orderStatus", order.getStatus());
            return Result.ok(data);
        }
        data.put("status", "NOT_FOUND");
        return Result.ok(data);
    }

    private void writeQueueStatus(Long orderId, String status) {
        stringRedisTemplate.opsForValue().set(
                SECKILL_QUEUE_KEY + orderId,
                status,
                SECKILL_QUEUE_TTL_MINUTES,
                TimeUnit.MINUTES
        );
    }

    /**
     * 排队状态是给前端轮询的旁路数据，不是下单的必要条件。
     * 写失败只记日志——绝不能让一次 Redis 抖动把已经预扣成功的订单变成失败，
     * 那样会白白少卖，且用户侧完全无感（他以为没抢到）。
     */
    private void writeQueueStatusSafe(Long orderId, String status) {
        try {
            writeQueueStatus(orderId, status);
        } catch (Exception e) {
            log.warn("排队状态写入失败，不影响下单结果, orderId={}, status={}", orderId, status, e);
        }
    }

    /**
     * 落库是否处于降级窗口：dbBreaker 打开或半开都算。
     * 半开也返回 true——半开意味着「还没确认恢复」，此刻承诺成功同样可能落空。
     */
    private boolean dbDegraded() {
        io.github.resilience4j.circuitbreaker.CircuitBreaker.State state =
                circuitBreakerRegistry.circuitBreaker("dbBreaker").getState();
        return state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.OPEN
                || state == io.github.resilience4j.circuitbreaker.CircuitBreaker.State.HALF_OPEN;
    }

}
