# 关单消失后，`SeckillReconcileTask` 的新职责

> wayfinder 票：**关单消失后，定时对账任务的新职责是什么**
> 所属 map：hmdp-pro 转型校园餐饮优惠平台 · 三条链路改造规格与测试策略
> 日期：2026-09-05
> 上游：docs/plans/2026-09-05-order-state-machine.md（§3 已给出骨架，本文确认并细化）
> 本文所有涉及现有代码的说法均已对照 `src/main/java` 与 `src/main/resources` 核实。

---

## 0. 结论（TL;DR）

| 问题 | 结论 |
| --- | --- |
| 补单保留到什么程度？ | **完整保留，一步不弱化**，并且**不改成直接落库**。票面"严重性下降"的前提不成立（见 §1）。 |
| 「有效订单」怎么定义？ | **这个概念消失。** 一行 = 一次领取，`COUNT(WHERE voucher_id=?)`，**不筛 `used`**。变量 `valid` 一并改名。 |
| 库存重算公式变不变？ | 变：`expected = max(0, initial_stock − COUNT(*))`。**在途单守卫整段删除**，其存在理由（等关单改写完账本）随关单一起消失。 |
| 7 天窗口 / 14 天 TTL 要不要调？ | **都不调。** 两者的推导链与关单无关。唯一要改的是 `RECONCILE_AFTER_END_MINUTES`：**2 → 8**。 |
| 要不要新增职责？ | **不新增步骤。** 撤回自愈是 ③ 的既有能力；`used` 一致性不需要巡检（CHECK 约束已在 DB 层强制）。唯一要补的是**对账指标**（当前为零，见 §6）。 |
| 执行顺序 | 三步变两步：**① 补单 → ② 库存重算**（编号全部重排，见 §5）。 |

---

## 1. 先纠正票面的一个前提：丢单后果**没有**下降

票里写：「消息丢了的后果从『用户付了钱没订单』变成『用户没领到券』——严重性下降」。

**核实后：这个前提不成立，而且反过来看会更严重。**

`VoucherOrderServiceImpl:257` 的原文是 `// 模拟支付：仅当订单未支付时改为已支付。`——`payOrder` 是用户手动调 `PUT /voucher-order/pay/{id}` 触发的状态翻转，**不涉及任何真实资金**。上游客票 §2.2 也定死了「已使用 ≠ 已支付，三方支付不接」。所以：

> **「用户付了钱」这件事在这个系统里从未真实发生过。**

改造前后丢单的实际后果**完全是同一件事**（`seckill.lua:23` 与 §2 的链路）：

1. Lua 已 `SISMEMBER` 放行并 `SADD seckill:order:{voucherId} userId`、已 `INCRBY stockKey -1`；
2. CREATE 消息丢失 → 订单表没有这一行；
3. 用户轮询 `getSeckillResult`：排队状态 `WAITING` 5 分钟后 TTL 过期 → 查 DB 无 → 回写 `NOT_FOUND`；
4. **他仍在 `seckill:order` 集合里**，`seckill.lua:23` 的 `SISMEMBER` 会永久返回 1 → 重复领券被拒（`return 2`）。

**结论：丢单 = 用户被扣掉一次机会、什么都拿不到、并且永久不能再领这张券。**
（同一条 `SADD`/不 `SREM` 的设计在上游客票 §2.4 有论证：「一人一次机会」是有意设计，防占位/防黄牛。）

**所以"保留还是弱化"这个问题，前提是错的。** 补单不是"保留到什么程度"的问题，而是**这条链路上唯一的自愈路径**——除了补单，没有任何路径能把这张券还给用户。

---

## 2. 决策一：补单完整保留，一步不弱化

### 2.1 三条理由（按强度排序）

**① 它是唯一的自愈路径。** 见 §1。丢单后用户既没有券、也领不了第二次，只有补单能修。

**② 它是「③ 库存重算不超卖」的前提，不只是用户体验问题。**
`initial_stock` 是**发放**库存，Redis 库存由 Lua 扣减。设券发放 100 份：

| | 扣减次数（= SCARD） | 订单行数（= COUNT） | `expected = 100 − COUNT` | 后果 |
| --- | ---: | ---: | ---: | --- |
| 无丢单 | 100 | 100 | 0 | 正确 |
| 丢 1 单且**补单成功** | 100 | 100 | 0 | 正确 |
| 丢 1 单且**补单没跑** | 100 | 99 | **1** | 凭空多出 1 份 → 真超卖 |

**补单就是让 `COUNT` 追上 `SCARD` 的机制。** 砍掉或弱化它，③ 就从"以账本为准覆盖漂移"退化成"系统性放大库存"。

**③ 它是「消息消费可靠性」四道防线里的最后一道。**
事务消息（主路径）→ 消费确认 + `RECONSUME_LATER` 重试（5 次）→ 死信队列（超限告警）→ **对账补单（收敛）**。
前两道恰恰证明了 MQ 不是绝对可靠；砍掉对账，等于自己承认前面三道够了——而混沌测试实测过 broker 重启丢单（`docs/chaos-test-report.md` §3.7/券 13 少 2 份）。

### 2.2 被否决的两种「简化」

| 方案 | 为什么不采纳 |
| --- | --- |
| **只告警不补发**（弱化为观测） | 告警不能自愈，而丢单是不可逆的（用户被集合锁死）。把唯一自愈路径换成一条日志，等于放弃这条兜底。 |
| **补单改为直接 `INSERT` 订单行**（不走 MQ） | 会绕开消费者里的三件事：① `uk_user_voucher` 冲突与事务回滚的既有处理；② `dbBreaker` 熔断；③ **排队状态不回写 `SUCCESS`**——用户轮询会一直停在 `WAITING`→`NOT_FOUND`，补了单用户还是看不到。而且"对账发现漏单后重新投递、消费端靠主键幂等去重"本身就是卖点的一部分，改成直接写库等于把这个叙事拆了。 |

### 2.3 补单唯一要改的是**注释里的后果描述**（不是逻辑）

`supplementMissingOrders` 的类注释讲"换新号补单会让用户轮询旧单号永远 NOT_FOUND"——**这段仍然成立，`seckill:claim` 复用原 orderId 的设计不动。**

要改的是把"丢单后果"的隐含认知纠正过来（§1）。建议在方法注释里补一句：

> 丢单的后果不是"少卖一份"，而是**该用户被 `seckill:order` 集合永久锁死却没拿到券**（Lua 已 sadd 且从不 srem）。补单是本系统唯一能把它修回来的路径。

### 2.4 顺带补一个缺口：反向不对称（当前静默）

现代码只处理 `COUNT < SCARD`（丢单）一个方向。`COUNT > SCARD`（订单比集合多，如 `SeckillMode` 中途从 FULL 切 EARLY 产生了不在集合里的订单）时，代码会照常做一次 `SMEMBERS` + 全量拉单，然后一条都补不了，静默返回 `false`。

建议加一个早退分支（不是新步骤，是省掉一次 O(N) 遍历并把异常暴露出来）：

```java
long claimed = voucherOrderService.lambdaQuery()
        .eq(VoucherOrder::getVoucherId, voucherId).count();
if (claimed == scard) { continue; }          // 基数相等 ⟺ 无丢单（既有早退）
if (claimed > scard) {
    // 订单用户 ⊆ 集合（一人一单），COUNT > SCARD 只可能是档位切换或集合被清理。
    // 补单对这种情况无能为力（没有任何 userId 在差集里），不做无谓的 SMEMBERS。
    // 误差方向是安全的：② 会算出偏小的 expected → 少卖，不是超卖。
    log.warn("对账：订单数({}) > 已扣库存集合({})，疑似档位切换或集合被清理，voucherId={}",
            claimed, scard, voucherId);
    continue;
}
```

**误差方向这一点值得单独记住**：对账的误差是**单侧的**——
`COUNT < SCARD` 靠 ① 补单收敛；`COUNT > SCARD` 时 ② 算出偏小的 expected → **少卖**（丢收入但安全）。没有任何路径会算出偏大的 expected。

---

## 3. 决策二：「有效订单」这个概念消失

上游 §3 已定：`expected = max(0, initial_stock − COUNT(*))`，不筛状态。本文补两点。

### 3.1 去掉的不只是过滤条件，还有这个词

`reconcileFinishedStocks` 里的局部变量叫 `valid`（有效订单）。改造后它等于"领走的数量"，**再叫 valid 会诱使人重新加过滤条件**——`valid` 这个词本身就暗示"有些订单无效"。

**改名：`valid` → `claimed`。** 并把上游客票 §3 的注释原样搬进代码：

```java
// 不筛 used —— 这不是省事，是语义要求：
// initial_stock 是「发放」库存（能领多少张），不是使用库存。券被领走的那一刻起
// 就永久占掉一个名额，核销与否不影响库存，因此 used=0 与 used=1 在库存口径上完全等价。
// 写成 COUNT(WHERE used = 0) 会把已核销的券从账本里剔除、凭空多放库存 —— 直接超卖。
// 恒真的过滤条件难发现，错误收窄的过滤条件更难发现，因为它看起来「更精确」。
long claimed = voucherOrderService.lambdaQuery()
        .eq(VoucherOrder::getVoucherId, voucherId).count();
int expected = Math.max(0, initial - (int) claimed);
```

### 3.2 在途单守卫：整段删除，且理由比"关单没了"更深一层

原注释（`:243-245`）：活动刚结束时关单仍在发生，此时算出的 expected 下一轮就会被关单改写。

除了"关单没了所以不需要等"，还有一层**现存耦合**被一起修掉了（上游客票 §3 已指出）：

> 守卫的判定依赖"pending 单最终会迁移出 valid 口径"，而**这个迁移动作本身正是被砍掉的关单**。

守卫等的是一个永远不会再发生的事件。留着它，`pending` 就永远是 0（没有 `status` 列了），守卫变成恒真的死代码。

---

## 4. 决策三：不新增职责

| 候选新职责 | 结论 | 理由 |
| --- | --- | --- |
| 运维撤回后的库存自愈 | **不新增**（③ 的既有能力） | 删掉账本行 → `expected = initial − COUNT(*)` 自动变大 → 下轮 ② 自动补回。不需要 Lua、不需要幂等标记（上游客票 §4）。**但要把这条写进类注释**，否则会有人以为撤回后要手工改库存。 |
| `used` / `use_time` 一致性巡检 | **不新增**（DB 已保证） | `chk_used_consistency` 在写入时拒绝脏数据，脏数据**根本存不进去**。再加一道定时巡检是重复防线且永远扫不出东西。 |
| 反向不对称纠偏（§2.4） | **不新增步骤** | 只是 ① 里的一个早退 + WARN。误差方向安全，不需要动作。 |
| 孤儿单清理（`seckill:order` 有、订单表无，且 claim 缺失） | **不新增** | 这就是 ① 补单在做的事。 |

**结论：对账从三步降到两步，且两步都变简单了。这是砍支付/关单带来的净收益，不要往回塞东西。**

---

## 5. 新的执行顺序（伪代码级）

```
@Scheduled(fixedDelay = 60_000)
reconcile():
    lock = redisson.getLock("lock:seckill:reconcile")
    locked = lock.tryLock(0, RECONCILE_LOCK_LEASE_SECONDS /* 50s */, SECONDS)
    if !locked: return                       # wait=0：他人在跑就放弃本轮
    try:
        supplemented = runStep("补单", this::supplementMissingOrders)
        if Boolean.TRUE.equals(supplemented):
            log.warn("对账：本轮发生补单，跳过库存重算，下轮再算")
        else:
            runStep("库存重算", this::reconcileFinishedStocks)
    finally:
        if lock.isHeldByCurrentThread(): lock.unlock()

────────────────────────────────────────────────────────

① 补单 supplementMissingOrders(): boolean
    now = LocalDateTime.now()
    vouchers = seckillVoucher.query(
        endTime >= now - 7d  AND  endTime < now - RECONCILE_AFTER_END_MINUTES)
    supplemented = false
    for v in vouchers:
        scard   = SCARD seckill:order:{v.voucherId}
        if scard == null or scard == 0: continue          # EARLY 模式不写集合
        claimed = COUNT(tb_voucher_order WHERE voucher_id = v)   # 走 idx_voucher
        if claimed == scard: continue                     # 基数相等 ⟺ 无丢单（O(1) 早退）
        if claimed >  scard:                              # ★ 新增早退，见 §2.4
            log.warn("订单数 > 集合，疑似档位切换或集合被清理"); continue
        # 差集：集合里有、订单表里没有的 userId
        for userId in SMEMBERS(...) \ 已落库 userId:
            order.id = HGET seckill:claim:{v} userId      # 复用用户入口拿到的原 orderId
                       ?? uidGenerator.getUID()           # claim 缺失 → 新号 + WARN
            order.userId = userId; order.voucherId = v
            try: rocketMQProducer.sendOrderCreate(order); supplemented = true
            catch: log.error(...)                          # 下轮再试，消费者主键幂等
    return supplemented

────────────────────────────────────────────────────────

② 库存重算 reconcileFinishedStocks(): void
    now = LocalDateTime.now()
    finished = seckillVoucher.query(
        endTime >= now - 7d  AND  endTime < now - RECONCILE_AFTER_END_MINUTES)
    for v in finished:
        initial = v.initialStock
        if initial == null or initial <= 0:
            log.warn("跳过重算：缺少 initialStock")          # 只跳重算，不跳续期
        else:
            claimed  = COUNT(tb_voucher_order WHERE voucher_id = v)   # ★ 不筛 used，§3.1
            expected = max(0, initial - claimed)
            if db.stock != expected or redis != expected:
                log.warn("重算：db={} redis={} → expected={}（初始={}, 已领={}）", ...)
                SET   seckill:stock:{v} = expected
                UPDATE tb_seckill_voucher SET stock = expected WHERE voucher_id = v
        # 续期必须无条件：被任何 skip 分支绕过都会让 key 重新永生
        EXPIRE seckill:stock:{v}  14d
        EXPIRE seckill:order:{v}  14d
        EXPIRE seckill:claim:{v}  14d
```

**顺序不可换的理由不变**：账本修复（①）必须先于派生值重算（②）。改造后这个理由**更强**——② 的公式不筛状态，① 补进去的每一行都会让 `expected` 变小，先算就会算大（多放库存）。"本轮补过单就跳过重算"这条实测修复（`chaos-test-report.md` §3.9 已修①）**必须原样保留**。

---

## 6. 参数与常量清单

### 6.1 要删的

| 常量 / 代码 | 位置 | 理由 |
| --- | --- | --- |
| `ORDER_TIMEOUT_MINUTES = 15` | `SeckillReconcileTask:53` | 关单阈值，随 ① 关单一起删 |
| `CLOSE_SCAN_BATCH_SIZE = 1000` | `SeckillReconcileTask:66` | 关单分批扫描专用 |
| `closeTimeoutOrders()` 整个方法 | `SeckillReconcileTask:130-148` | 上游客票 §3 |
| `runStep("关单", ...)` 调用行 | `SeckillReconcileTask:87` | 同上 |
| 类注释里的「①关单 → ②补单 → ③库存重算」 | `SeckillReconcileTask:31` | 改为「①补单 → ②库存重算」 |
| 在途单守卫 + `pending` 查询 + `valid` 变量 | `SeckillReconcileTask:243-256` | §3.2 / §3.1 |

### 6.2 要改的

| 项 | 现 | 新 | 判据 |
| --- | --- | --- | --- |
| `RECONCILE_AFTER_END_MINUTES` | `2` | **`8`** | 见 §6.3，**唯一的实质参数变更** |
| 步骤编号 ①② 在代码注释 / `hmdp-schema.sql` 索引注释 / `chaos-test-report.md` / `resume-hmdp-pro.md` / `tech/**` 里的所有出现 | ①关单 ②补单 ③重算 | **①补单 ②重算** | 编号本身就是契约，散落在 ≥5 处文档里（`schema.sql` 的索引注释直接写着"覆盖 ②③"）。**这一项极易漏改，建议单独 grep `②`/`③` 核对** |
| `hmdp-schema.sql` 索引注释 | 「②补单（`WHERE voucher_id=?`）与③库存重算…共用，后者用到完整两列」 | 改为「② 共用（`WHERE voucher_id=?`，不筛状态）」 | 索引降为 `idx_voucher(voucher_id)`，注释里的"用到完整两列"已不成立 |

### 6.3 `RECONCILE_AFTER_END_MINUTES`：2 → 8 的完整推导

**现状的注释与取值是矛盾的**（`SeckillReconcileTask:54-55`）：

```java
/** 秒杀结束多久后允许补单/重算（等消费重试链排空，重试 cadence 最长约 6 分钟） */
private static final int RECONCILE_AFTER_END_MINUTES = 2;
```

注释说要等约 6 分钟，取值却是 2 分钟。

**重试链真实多长（对着 `docker/rocketmq/broker.conf:34` 的自定义档位表算）：**

```
messageDelayLevel = 1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 15m 20m 30m 1h 2h
                    L1  L2   L3  L4  L5 L6 L7 L8 L9 L10 L11 ...
```

`OrderMQConsumer:76` `MAX_RECONSUME_TIMES = 5`，RocketMQ 并发消费失败重投的档位是 `3 + reconsumeTimes`：

| 第 n 次重试 | 档位 | 间隔 | 累计 |
| --- | --- | --- | --- |
| 1 | L3 | 10s | 10s |
| 2 | L4 | 30s | 40s |
| 3 | L5 | 1m | 1m40s |
| 4 | L6 | 2m | 3m40s |
| 5 | L7 | 3m | **6m40s** |

第 5 次仍失败 → 转死信 topic（`OrderDeadLetterConsumer`）。

**所以要等 ≈ 7 分钟，消息才"要么落库要么进 DLQ"，此时 ① 的判定才是可信的。取 8 = ceil(400s/60) + 1 分钟余量。**

**2 分钟的实际后果（不致命，但很脏）：** 活动结束后 2~8 分钟这一窗口内，仍在重试中的单会被误判成丢单 →
- 重复补发 CREATE（消费者主键幂等，**安全**）；
- 本轮跳过重算（**安全**）；
- 打 `WARN "对账补单"` 噪音，导致"补单数"这个口径**混入"在途重试数"**，告警失真。

**建议改为 8，并在常量注释里写清推导链**（`MAX_RECONSUME_TIMES=5` → 档位 L3~L7 → 累计 400s → 取 8 分钟）。

> ⚠️ 需要一次性实测确认：RocketMQ 4.9 的重投档位是否确为 `3 + reconsumeTimes`。验证方法——停 MySQL 制造消费失败，观察消息从首次失败到进入 DLQ 的墙钟时间。**这是全文中唯一没有 100% 源码佐证的假设。**

### 6.4 不动的（以及为什么）

| 常量 | 值 | 判据 |
| --- | --- | --- |
| `RECONCILE_WINDOW_DAYS` | `7` | **判据与关单无关**：无下界则每轮全量扫历史券，成本随历史无限增长（`SeckillReconcileTask:56` 原注释）。关单消失不改变这条。 |
| `KEY_TTL_AFTER_END_SECONDS` | `14d` | **判据是「TTL 必须严格大于对账窗口」**：窗口内的券每轮续到 14d，出窗（>7d）后自然死亡。14 = 7（窗口）+ 7（余量）。若 TTL < 窗口，key 会在对账还该管它的时候先死，账本证据消失。 |
| `RECONCILE_LOCK_LEASE_SECONDS` | `50` | 判据是"小于调度间隔 60s，实例宕机后锁快速自释放"，与关单无关。 |
| `@Scheduled(fixedDelay)` | `60_000` | 不变。 |

**已知耦合（本次不改，但必须记录）**：`seckill.lua:38` 把 `seckill:claim` 的 TTL 硬编码为 `1209600`（=14d），与 `RedisConstants.SECKILL_CLAIM_TTL_SECONDS` 是**两处独立的字面量**，靠注释维系一致。**将来若调窗口或 TTL，两处都要改。**

### 6.5 ⚠️ 不要顺手精简 `broker.conf` 的延迟档位表

关单消失后，自定义档位表里 **L15（15m）失去了唯一使用者**，而 `ORDER_TIMEOUT_DELAY_LEVEL = 15` 常量也要删。此时很容易想"那把 L15 去掉/改回默认表"。

**不能动。** `messageDelayLevel` 是**延迟消息与消费重试共用的同一张表**：关单不用了，但 **L3~L7 仍被消费重试使用**（§6.3）。改表会静默改变重试节奏，进而让 `RECONCILE_AFTER_END_MINUTES = 8` 的推导失效——而且失效方式极其隐蔽（对账开始误判丢单，只表现为 WARN 变多）。

`broker.conf:34` 建议只加一行注释说明"L15 曾用于延迟关单，现保留以免改动重试节奏"。

---

## 7. 附带发现（对着源码核实）

### 7.1 🔴 对账任务当前**零指标**

`SeckillMetrics` 里与对账相关的只有 `ORDER_TIMEOUT_SEND_ERROR`（`SeckillMetrics:24`，随关单一起删）。**补单与库存重算没有任何指标，只有 `log.warn`。**

这与 map Notes 里"本项目把每个判断分支都暴露成带 tag 的指标，黑盒从 `/actuator/prometheus` 就能断言到内部决策"的说法**直接冲突**——对账恰恰是唯一没有指标暴露的分支。而它又是"消息消费可靠性"卖点的最后一环。

**建议新增（规格，不在本 map 内实现）：**

| 指标 | 类型 | tag | 用途 |
| --- | --- | --- | --- |
| `hmdp.reconcile.supplement_total` | Counter | — | 补单条数。这是"丢单率"的唯一量化口径 |
| `hmdp.reconcile.restock_adjusted_total` | Counter | — | 库存被改写的次数（>0 = 发生过漂移） |
| `hmdp.reconcile.round_total` | Counter | `outcome=skipped_supplement / converged` | 对账是否走完两步 |
| `hmdp.reconcile.drift` | Gauge | — | 当前 `abs(redis − db)`，恒 0 即一致 |

**tag 红线：`voucherId` 绝不能进 tag**（高基数会打爆 Prometheus），只能进日志。

→ 已开新票承接：**「对账任务的观测口径：补单与库存重算怎么被断言」**。

### 7.2 🟡 `chaos-test-report.md` 有一处**已被代码追上**的过期描述

`chaos-test-report.md:245` 写道：

> `supplementMissingOrders` 用 `lt(endTime, now)`、`reconcileFinishedStocks` 用 `lt(endTime, now-2min)`，两个阈值不一致。

**核实：现在两处都用的是 `now.minusMinutes(RECONCILE_AFTER_END_MINUTES)`**（`SeckillReconcileTask:165` 与 `:234`），已经是同一个常量。这段描述是修复前的快照，文档没跟上。

处置：**改写**（不是删除）——把"两个阈值不一致"改成"两者共用 `RECONCILE_AFTER_END_MINUTES`，已一致"，并保留它讲的原理（一致才不会有"补单跑了重算没跑"的窗口）。

### 7.3 券 13 的"进行中丢单无兜底"仍然成立（与关单无关）

`chaos-test-report.md` 待决策 #5：「进行中券丢单无人兜底，库存凭空蒸发（券 13 实测少 2 份），建议补单时只补订单、不回补库存（回补即超卖）」。

关单消失**不影响**这条：对账只在 `endTime < now − 8min` 的券上跑，进行中的券丢单依然无人兜底。改造后这条的正确表述是"**接受，活动结束后由 ② 统一按账本重算收敛**"——与 §1 的"丢单 = 用户永久失去这张券"合起来看，**进行中的丢单从"库存蒸发"升级为"用户永久失去资格且当下无法自愈"**，这条待决策的优先级实际上**上升**了。

---

## 8. 对下游票 / map 的输入

| 下游 | 本文给它的输入 |
| --- | --- |
| **删除清单：支付域与关单域的代码、DDL 与文档边界** | `SeckillReconcileTask` 侧：删常量 2 个（`ORDER_TIMEOUT_MINUTES` / `CLOSE_SCAN_BATCH_SIZE`）、删方法 1 个、删调用行 1 个、删守卫 1 段、改编号 ①②③→①②（含 `schema.sql` 索引注释）。`broker.conf` 的档位表**不要动**。 |
| **抢券链路测试策略：并发正确性如何证明** | 对账可断言的最小面：① 造丢单（拦掉 CREATE 消息）→ 观察 `hmdp.reconcile.supplement_total` 递增且下一轮 `COUNT == SCARD`；② 核销一行后重算，`expected` **不变**（这是 §3.1 那条注释的回归测试，防止有人"优化"成 `COUNT(WHERE used=0)`）。 |
| **新开：对账任务的观测口径** | §7.1 的四个指标 + tag 红线。 |
| map 的 Not yet specified | 无新增。本案没有产生新的 fog——补单保留、公式、参数三项都已在本文定死。 |

## 9. 不在本文范围内

- 实际改代码、跑 DDL、提 PR —— map 内只产规格。
- 对账是否也覆盖**进行中**的券（§7.3）—— 不是关单消失带来的问题，本轮不动。
- 指标的具体埋点实现 —— 由新票承接。
