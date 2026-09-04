package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.baidu.fsg.uid.UidGenerator;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.exception.ErrorCode;
import com.hmdp.exception.SystemException;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.RocketMQProducer;
import com.hmdp.mq.SeckillTxContext;
import com.hmdp.observability.ObservabilityRecorder;
import com.hmdp.observability.SeckillMetrics;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.SeckillMode;
import com.hmdp.utils.UserHolder;
// 注意：注解 @Bulkhead 与核心类 io.github.resilience4j.bulkhead.Bulkhead 同名，
// 同文件里只 import 注解，Type.SEMAPHORE 用全限定名引用（见下方注解）
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

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
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

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

    /** 秒杀方案：A=入口预扣+事务消息；B=限流入队+消费者校验 */
    @Value("${seckill.mode:A}")
    private String seckillMode;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    /** 对比测试：只扣库存、不做 Lua 一人一单 */
    private static final DefaultRedisScript<Long> SECKILL_STOCK_ONLY_SCRIPT;
    /** 方案 B 消费者：Redis claim 库存+一人一单 */
    private static final DefaultRedisScript<Long> SECKILL_CLAIM_SCRIPT;

    /** Redis 开关：seckill:test:protection = FULL|HEIMA|EARLY（默认 FULL） */
    public static final String ONE_ORDER_PROTECTION_KEY = "seckill:test:protection";

    /** 消费端一人一单锁租期（秒）：落库耗时上界，显式 lease 禁用 watchdog */
    private static final long ORDER_LOCK_LEASE_SECONDS = 10;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_STOCK_ONLY_SCRIPT = new DefaultRedisScript<>();
        SECKILL_STOCK_ONLY_SCRIPT.setLocation(new ClassPathResource("seckill-stock-only.lua"));
        SECKILL_STOCK_ONLY_SCRIPT.setResultType(Long.class);

        SECKILL_CLAIM_SCRIPT = new DefaultRedisScript<>();
        SECKILL_CLAIM_SCRIPT.setLocation(new ClassPathResource("seckill-claim.lua"));
        SECKILL_CLAIM_SCRIPT.setResultType(Long.class);
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
        // 模拟支付：仅当订单未支付时改为已支付
        boolean updated = update()
                .set("status", ORDER_STATUS_PAID)
                .set("pay_time", LocalDateTime.now())
                .eq("id", orderId)
                .eq("status", ORDER_STATUS_UNPAID)
                .update();
        if (!updated) {
            return Result.fail("订单不存在或状态不允许支付");
        }
        // 延迟关单消息不可撤销，到点后 cancelTimeoutOrder 通过状态乐观更新自动跳过已支付订单，无需额外处理
        return Result.ok("支付成功");
    }

    /**
     * MQ 消费者异步落库入口：幂等创建订单
     * 方案 A：入口已扣 Redis，此处落库 + DB 二次扣库存
     * 方案 B：此处先 claim Redis，再落库，并回写排队终态
     *
     * <p>P2 容错：{@code dbBreaker}（50%/窗口 20/半开 15s）包裹整个落库流程。
     * 熔断打开后方法入口即抛 {@code CallNotPermittedException}，消费端 catch 后返回
     * RECONSUME_LATER 延迟重投——不碰 DB 也不碰 Redis，配合消息重试上限 + 死信 +
     * 对账补单形成完整兜底阶梯。
     *
     * <p>已知权衡：方法内的 Redisson 锁 / claim 脚本失败也会计入 dbBreaker
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
        boolean modeB = SeckillMode.B.equalsIgnoreCase(voucherOrder.getSeckillMode());
        String protection = oneOrderProtection();
        // EARLY=黑马最初仅靠事后 count（本分支直接不加锁、不预查，制造并发窗口）
        // HEIMA=Lua + Redisson + count（课程终态常见组合，不含唯一索引兜底语义）
        // FULL=三级：Lua + Redisson + count + 唯一索引捕获
        boolean early = "EARLY".equals(protection);
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        boolean locked = false;
        if (!early) {
            // 显式 wait/lease：等 2s（并发的重复消息快速让位，而不是挂 30s），
            // lease=10s 硬上限禁用 watchdog——消费线程挂死时锁最迟 10s 自动释放。
            // 中断视为拿锁失败，走下面的失败分支（方案 B 抛错重试 / 方案 A 判重复）
            try {
                locked = redisLock.tryLock(2, ORDER_LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                locked = false;
            }
            if (!locked) {
                if (modeB) {
                    throw new RuntimeException("获取下单锁失败，等待重试, orderId=" + orderId);
                }
                log.error("不允许重复下单！");
                return;
            }
        }

        try {
            if (!early) {
                int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if (count > 0) {
                    if (modeB) {
                        writeQueueStatus(orderId, SeckillMode.QUEUE_SUCCESS);
                    } else {
                        log.error("不允许重复下单！");
                    }
                    return;
                }
            }

            if (modeB) {
                Long claim = stringRedisTemplate.execute(
                        SECKILL_CLAIM_SCRIPT,
                        Collections.emptyList(),
                        voucherId.toString(), userId.toString()
                );
                if (claim == null || claim == 1L) {
                    writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_STOCK);
                    log.warn("方案B库存不足, orderId={}, userId={}, voucherId={}", orderId, userId, voucherId);
                    return;
                }
            }

            boolean success = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                if (modeB) {
                    stringRedisTemplate.opsForValue().increment(SECKILL_STOCK_KEY + voucherId);
                    stringRedisTemplate.opsForSet().remove("seckill:order:" + voucherId, userId.toString());
                    writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_STOCK);
                }
                log.error("库存不足！orderId={}", orderId);
                return;
            }

            voucherOrder.setStatus(ORDER_STATUS_UNPAID);
            try {
                save(voucherOrder);
            } catch (DuplicateKeyException e) {
                seckillVoucherService.update()
                        .setSql("stock = stock + 1")
                        .eq("voucher_id", voucherId)
                        .update();
                if (modeB) {
                    writeQueueStatus(orderId, SeckillMode.QUEUE_SUCCESS);
                }
                log.warn("重复订单消息，唯一索引拦截并回补DB库存, userId={}, voucherId={}", userId, voucherId);
                return;
            }
            try {
                rocketMQProducer.sendOrderTimeout(voucherOrder.getId());
            } catch (Exception e) {
                // 关单延迟消息发送失败：只记日志的话订单会永远停在「未支付」（无自动关单保障）。
                // 升级为：记入 Redis 重试集合 → 对账任务每轮重发（SeckillReconcileTask#retryTimeoutMessages）
                // → 重发后仍到不了点还有 closeTimeoutOrders 按 createTime 扫描关单兜底。
                // cancelTimeoutOrder 是 CAS 幂等（仅未支付才关），重复触发无副作用。
                seckillMetrics.orderTimeoutSendError();
                stringRedisTemplate.opsForSet().add(
                        SECKILL_TIMEOUT_RETRY_KEY, voucherOrder.getId().toString());
                log.error("超时关单延迟消息发送失败，已记入重试集合等待对账重发, orderId={}",
                        voucherOrder.getId(), e);
            }
            if (modeB) {
                writeQueueStatus(orderId, SeckillMode.QUEUE_SUCCESS);
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("MQ 异步落库失败，等待重试, orderId={}", orderId, e);
            throw new RuntimeException(e);
        } finally {
            // 租期内未完成时锁已自动过期，此处再 unlock 会抛 IllegalMonitorStateException
            if (locked && redisLock.isHeldByCurrentThread()) {
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
     * 都只能拒绝下单，绝不能放行——Mode A 的不超卖证明唯一依赖 Redis Lua 的原子扣减，
     * Redis 挂了还放行 = 拆掉正确性屏障，直接超卖。
     * 两个异常（舱壁满 / 熔断打开）由全局异常处理器统一转成 503 + SYS_BUSY。
     */
    @Bulkhead(name = "seckillBulkhead", type = Bulkhead.Type.SEMAPHORE)
    @CircuitBreaker(name = "redisBreaker")
    @Override
    public Result seckillVoucher(Long voucherId) {
        if (SeckillMode.B.equalsIgnoreCase(seckillMode)) {
            return seckillVoucherModeB(voucherId);
        }
        return seckillVoucherModeA(voucherId);
    }

    /**
     * 方案 A：事务消息 + 入口 Lua（库存/一人一单），同步返回成败
     */
    private Result seckillVoucherModeA(Long voucherId) {
        // 埋点：计时 + 结果。reason 贯穿所有分支，finally 统一落定，
        // 这样任何一个 return 路径都不会漏统计
        ObservabilityRecorder.Sample sample = seckillMetrics.startSeckill();
        SeckillMetrics.Reason reason = SeckillMetrics.Reason.SUCCESS;
        try {
            Long userId = UserHolder.getUser().getId();
            long orderId = uidGenerator.getUID();
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setSeckillMode(SeckillMode.A);

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
                // 落库降级语义（P2 关键决策）：Lua 成功只代表 Redis 预扣成功，订单此刻尚未落库，
                // 要等消费者落库。dbBreaker 打开/半开 = 落库遥遥无期——这时返回"成功"是在撒谎，
                // 用户会看到「下单成功但订单消失」。说谎的降级比明确的报错更糟：
                // 诚实返回 ORDER_PROCESSING（业务码 1100），前端凭 code 展示「处理中」并轮询
                // getSeckillResult；最终一致性由消费重试 + 死信 + 对账补单保证。
                if (dbDegraded()) {
                    reason = SeckillMetrics.Reason.DB_DEGRADED;
                    seckillMetrics.degraded("dbBreaker", SeckillMetrics.Reason.DB_DEGRADED);
                    return Result.fail(ErrorCode.ORDER_PROCESSING);
                }
                return Result.ok(orderId);
            }
            if (r == 1) {
                reason = SeckillMetrics.Reason.STOCK_OUT;
                return Result.fail("库存不足");
            }
            if (r == 2) {
                reason = SeckillMetrics.Reason.REPEAT;
                return Result.fail("不能重复下单");
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

    /**
     * 方案 B：入口只发消息 + 写 WAITING，立即返回 orderId；限流在校验前由网关挡，校验在消费者
     */
    private Result seckillVoucherModeB(Long voucherId) {
        ObservabilityRecorder.Sample sample = seckillMetrics.startSeckill();
        SeckillMetrics.Reason reason = SeckillMetrics.Reason.SUCCESS;
        try {
            Long userId = UserHolder.getUser().getId();
            long orderId = uidGenerator.getUID();
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setSeckillMode(SeckillMode.B);

            // Redis 不可用时这一步就会抛——上抛 SystemException 让 redisBreaker 记到这次失败，
            // 吞掉返回 Result 会让熔断器在 Redis 挂掉时依然显示"健康"
            try {
                writeQueueStatus(orderId, SeckillMode.QUEUE_WAITING);
            } catch (Exception e) {
                reason = SeckillMetrics.Reason.SYSTEM_ERROR;
                throw new SystemException(ErrorCode.SYS_REDIS_UNAVAILABLE, "排队状态写入失败");
            }
            try {
                SendResult sendResult = rocketMQProducer.sendOrderCreate(order);
                if (sendResult.getSendStatus() != SendStatus.SEND_OK) {
                    // 非 OK 视同发送失败：抛异常让 mqBreaker 学到，QUEUE_FAIL_SYSTEM 在 catch 统一写
                    throw new SystemException(ErrorCode.SYS_MQ_UNAVAILABLE,
                            ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
                }
            } catch (SystemException e) {
                writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
                reason = SeckillMetrics.Reason.MQ_SEND_ERROR;
                // 同方案 A：不上抛，避免 MQ 故障被外层 redisBreaker 误记为 Redis 失败
                return Result.fail(e.getErrorCode(), e.getMessage());
            } catch (Exception e) {
                log.error("方案B发消息失败, userId={}, voucherId={}", userId, voucherId, e);
                writeQueueStatus(orderId, SeckillMode.QUEUE_FAIL_SYSTEM);
                reason = SeckillMetrics.Reason.MQ_SEND_ERROR;
                return Result.fail(ErrorCode.SYS_MQ_UNAVAILABLE, ErrorCode.SYS_MQ_UNAVAILABLE.getMessage());
            }
            // 方案 B 的「成功」指成功入队，真正的库存校验在消费者侧，
            // 落库结果由 hmdp_order_consume_* 与 hmdp_seckill_result_*（消费侧补）反映。
            // dbBreaker 打开 = 消费端落库无期，与方案 A 同一语义：诚实返回「处理中」
            if (dbDegraded()) {
                reason = SeckillMetrics.Reason.DB_DEGRADED;
                seckillMetrics.degraded("dbBreaker", SeckillMetrics.Reason.DB_DEGRADED);
                return Result.fail(ErrorCode.ORDER_PROCESSING);
            }
            return Result.ok(orderId);
        } finally {
            seckillMetrics.finishSeckill(sample, SeckillMode.B, reason);
        }
    }

    @Override
    public Result getSeckillResult(Long orderId) {
        if (orderId == null) {
            return Result.fail("订单号不能为空");
        }
        Map<String, Object> data = new HashMap<>(4);
        data.put("orderId", orderId);
        data.put("mode", seckillMode);

        String status = stringRedisTemplate.opsForValue().get(SECKILL_QUEUE_KEY + orderId);
        if (status != null) {
            data.put("status", status);
            return Result.ok(data);
        }
        // 无排队状态：方案 A 或状态已过期 → 查订单表兜底
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
