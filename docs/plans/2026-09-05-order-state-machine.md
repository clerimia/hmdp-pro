# 订单状态机与「领取」语义定义

> wayfinder 票：**砍掉支付与关单后，订单状态机与「领取」语义如何定义**
> 所属 map：hmdp-pro 转型校园餐饮优惠平台 · 三条链路改造规格与测试策略
> 日期：2026-09-05
> 本文所有涉及现有代码的说法均已对照 `src/main/java` 与 `src/main/resources/db/hmdp-schema.sql` 核实。

---

## 0. 结论（TL;DR）

**状态用一个布尔字段 `used` 表示：创建默认 `0`（已领取未使用），核销时用 CAS 切 `1`（`WHERE id=? AND used=0`）保证幂等。删掉 `status` 枚举列与围绕支付建的 `pay_type` / `pay_time` / `refund_time`，保留 `use_time` 记录核销时刻。**

**核销接口不在本项目范围内**（见 §10 范围声明）——`used=1` 由商家端/线下动作写入，本项目只保证数据模型能表达它，并在种子数据里提供样本。

> **2026-09-05 两次修订**：初版结论是「删掉 `status`，变无状态账本行」；怡霖提出需要「已使用」后改为两态枚举；怡霖再提出「一个布尔值 + CAS 切 1」后定为现方案。**§1（现状核实）的事实部分始终不变**，§2 起的决策以本节为准。

| 问题 | 结论 |
| --- | --- |
| 「领取成功」是不是终态？ | **不是。** 券到店核销后变「已使用」，这是业务上真实发生的第二次迁移——只是它发生在商家端/线下，**不在本项目的接口范围内**。 |
| 状态用什么表示？ | **一个布尔字段 `used`**：创建默认 `0`（已领取未使用），核销时 **CAS 切 1** 保证幂等。**不用 `status` 枚举**——两态里「已领取」对每行恒真，不携带信息。删 `pay_type`/`pay_time`/`refund_time`，留 `use_time`。**对账口径不变**（见 §3）——券被领走就占库存，用不用都占。 |
| 存量数据怎么办？ | **改种子数据**，9 条订单清掉支付语义列，并**补造 `used=1`（已使用）的样本**——核销接口不做，测试基线里就必须有这个状态的样本。 |
| 领券 / 抢券 / 秒杀怎么叫？ | **人读的地方改「领券」，代码与 Redis key 保留 `seckill`。** 339 处标识符改名里最关键的是 `seckill:stock:` 等 Redis key 前缀——改名等于换 key 空间，对账与 14d TTL 依赖它，风险远大于收益。 |
| 核销接口做不做？ | **不做，也不测。** 三条被测链路中的「领券」只创建行（`used` 走 DB 默认 `0`）；`used=1` 的写入方在线下。见 §10。 |

---

## 1. 现状核实：今天真实存在的状态机

先把"以为有的"和"真有的"分开。列一遍 `tb_voucher_order` 的每一列在 `src/main/java` 里的真实引用：

| 列 | schema 定义 | Java 代码引用 | 判定 |
| --- | --- | --- | --- |
| `id` | bigint，UidGenerator 生成 | 主键，落库幂等键 | 保留 |
| `user_id` / `voucher_id` | 唯一索引 `uk_user_voucher` | 一人一单的唯一落点 | 保留 |
| `create_time` | timestamp | 关单扫描（要删）、对账窗口 | 保留 |
| `update_time` | timestamp | 无主动引用 | 保留（审计） |
| `pay_type` | `支付方式 1余额/2支付宝/3微信` | **零引用**（仅 entity 字段声明 + seed 赋值） | **删** |
| `status` | `1未支付 2已支付 3已核销 4已取消 5退款中 6已退款` | 见下 | **删** |
| `pay_time` | timestamp | **仅 `payOrder` 一处 set**（要删） | **删** |
| `use_time` | `核销时间` | **零引用**（仅 entity 声明 + seed 赋值） | **删** |
| `refund_time` | `退款时间` | **零引用**（仅 entity 声明 + seed 赋值） | **删** |

### 1.1 `status` 上真实存在的迁移边

`RedisConstants` 里只有四个常量：`ORDER_STATUS_UNPAID=1`、`PAID=2`、`VERIFIED=3`、`CANCELLED=4`——注释里提到的 5（退款中）/ 6（已退款）**连常量都没有**。

代码里全部的写入点：

| # | 迁移 | 位置 | 砍支付/关单后 |
| --- | --- | --- | --- |
| 1 | ∅ → 1（未支付） | `VoucherOrderServiceImpl#createOrderFromMQ` 事务内 `setStatus(ORDER_STATUS_UNPAID)` | **唯一的写入点** |
| 2 | 1 → 2（已支付）+ 写 `pay_time` | `VoucherOrderServiceImpl#payOrder`（CAS，带 `user_id` 防越权） | 删 |
| 3 | 1 → 4（已取消）+ 回补库存 | `VoucherOrderServiceImpl#cancelTimeoutOrder`，由 MQ 延迟消息与对账 ①关单两步触发 | 删 |
| 4 | 2 → 3（已核销） | **没有任何代码。** 只在 `RedisConstants` 注释里以"核销流程当前未实现，防御性对齐"存在 | 从未存在 |
| 5 | → 5 / 6（退款） | **没有任何代码**，也无常量 | 从未存在 |

**所以今天真实的状态机是 `∅ → 1 → {2, 4}`，而 3/5/6 是三个只活在注释里的态。** 砍掉支付与关单之后，剩下的只有 `∅ → 1`：一条边、一个态、零次迁移。

---

## 2. 决策一：状态用一个布尔字段 `used`

> 本节修订过两次：初版结论是「删掉 `status`，变无状态账本行」；怡霖提出需要「已使用」后改为两态枚举；再提出「一个布尔值 + CAS 切 1」后定为现方案。**以本节为准。**

### 2.1 为什么从「无状态」改成「有状态」

初版判断的依据是「用户领到手之后系统内没有任何后续动作」，于是推出「一态零迁移 = 常量 = 删掉」。**这个前提被推翻了**：券到店会被核销，这是业务上真实发生的第二次迁移。

状态字段是**给异步流程打的书签**——记录"这笔资源现在轮到谁来处理"。**有真实迁移时它是资产，没有时它是负债。** 现在有了 `已领取 → 已使用`，状态从负债变回资产——只是存储形式最终选了布尔而非枚举（见 §2.3）。

### 2.2 「已使用」≠「已支付」（术语必须先掰开）

这是本次修订最容易搞混的地方，两者是完全不同的事件：

| | 已使用 | 已支付 |
| --- | --- | --- |
| 业务性质 | **履约**（券被核销） | **交易**（付钱买券） |
| 发生位置 | 商家端 / 线下 | 系统内，需接三方支付 |
| 本项目 | **需要** | **不需要** |

券是免费领的，用户到店后**线下付钱给商家，系统不参与这笔支付**——所以三方支付不在本项目范围内，「已支付」这个态也就不需要。需要的只是「已使用」。

**连带结论：三方支付不接，`pay_type` / `pay_time` 照删。** 状态字段回来不等于支付回来。

### 2.3 状态用一个布尔字段 `used` 表示，CAS 切 1

`status` 的 6 个取值逐个过一遍，结论是**一个都留不下**：

| 原值 | 处置 | 理由 |
| --- | --- | --- |
| 1 未支付 | **删除** | 三方支付不接 |
| 2 已支付 | **删除** | 三方支付不接 |
| 3 已核销 | **换成 `used = 1`** | 唯一真正需要存的信息 |
| 4 已取消 | **删除** | 超时关单已砍（撤回走 DELETE，见 §4） |
| 5 退款中 | **删除** | 从来只有注释没有常量 |
| 6 已退款 | **删除** | 同上 |

**「创建订单行 = 已领取」**（怡霖 2026-09-05 定义）。表名沿用 `order`，但业务语义上它不是交易订单表，而是**用户-优惠券关联表**——一行记录「哪张券被谁领走了」。这个认知解释了两件事：

- 为什么支付字段全部该删——关联表不承载交易；
- 为什么 `uk_user_voucher(user_id, voucher_id)` 才是本表的自然主键——一人一券正是关联表的固有约束。

**它同时也否掉了 `status` 枚举列**：既然「存在这一行」就等于「已领取」，那么「已领取」这个取值对每一行恒真，**它不携带任何信息**——留着就是一个恒真的常量列。真正要存的只有一个比特：**是否已使用**。

> **判据**：两态里如果有一态是「尚未发生」，就不该用枚举，用一个带默认值的布尔就够。枚举的价值在于表达**多个互斥的当前阶段**，而不是表达一个**布尔 + 一个永不迁移的默认值**。

#### 字段定义与两个写入时刻

```sql
`used` tinyint(1) unsigned NOT NULL DEFAULT 0
    COMMENT '是否已使用：0=已领取未使用；1=已使用（核销）'
```

| 时刻 | 动作 |
| --- | --- |
| 领券落库 | **不写这一列**，靠 DB 默认 `0`。落库 SQL 少一列，也杜绝了「落库时误写成 1」 |
| 核销（线下由商家端触发） | **CAS 切 1 并写时间**：`UPDATE tb_voucher_order SET used = 1, use_time = NOW() WHERE id = ? AND used = 0` |

**CAS 就是幂等的全部实现**（怡霖 2026-09-05 定）：

- 影响行数 `1` = 首次核销成功；
- 影响行数 `0` = 已核销过，**直接跳过，不覆盖 `use_time`**。

不需要幂等标记、不需要分布式锁、不需要先查后写——**把状态判断压进 WHERE，就不存在 check-then-act 的竞态窗口**。这与 `payOrder` 现有写法是同一手法（它把 `user_id` 归属判断与 `status` 一起压进 WHERE 构成原子 CAS）。

**已知边界**：影响行数 `0` 时无法区分「已核销」与「订单不存在」。商家端若需要区分提示，得再查一次——但那是核销接口的职责，本项目不提供核销接口（§10）。

### 2.4 一人一单不依赖 `used`（这一条论证不变，仍然成立）

直觉上"有效订单"要靠状态过滤，实际两条防线都**不看状态**：

**DB 层** —— `uk_user_voucher(user_id, voucher_id)` 唯一索引**不带状态列前缀**。这意味着它对已取消的行同样生效：今天即使订单被取消，该用户也不能再领同一张券。索引的真实语义是「**一个用户对一张券，全生命周期最多一行**」。

**Redis 层** —— `seckill.lua` 用 `SISMEMBER seckill:order:{voucherId} userId` 拦截，而 `cancelTimeoutOrder` 的注释写死了这一点：

> 本方法不做 `seckill:order` 集合的 srem——「一人一次机会」是有意设计（防占位/防黄牛），取消只回补库存给别人买，本人不能再抢。

**结论：一人一单与 `used` 完全正交。** 语义收敛为一句更干净的话：

> **一人一单 = 「曾经领过」，不是「当前持有」。**

不论券处于「已领取」还是「已使用」，该用户都不能再领同一张券——`uk_user_voucher` 管的是"领过没有"，不是"用没用过"。

### 2.5 「已使用」是不是又一个死状态？（必须正面回答）

§7.1 我批评过 `SeckillMode.QUEUE_FAIL_REPEAT` / `FAIL_SYSTEM` 是「定义了但从未写入」的死状态。现在 `used = 1` 在**本项目代码里同样没有任何写入方**——那它是不是同一类问题？

**不是。区别在业务事件是否真实存在：**

| | `QUEUE_FAIL_REPEAT` / `FAIL_SYSTEM` | `used = 1`（已使用） |
| --- | --- | --- |
| 业务上有这个事件吗 | **没有**。落库重复的分支根本不写排队状态，这两个值是凭空定义的 | **有**。券到店确实会被核销 |
| 性质 | 代码债——写的人以为会有，实际没有 | **范围边界**——事件真实，只是写入方不在这个项目里 |
| 处置 | 补写入路径，或删常量 | 保留字段，**在 §10 写死"谁写它、怎么写"** |

**但这条辩护有个前提，不满足它就退化成死状态**：必须在规格里写明 `used=1` 的写入方是谁。所以 §10 是本文档不可省略的一节——**没有它，「已使用」和 `FAIL_REPEAT` 就没有区别。**

### 2.6 反方意见与回应

**"核销接口不做，那留着 `used` 有什么用？"** 三个用途，都不需要核销接口：
1. **数据模型完整性** —— 关联表要能表达"这张券已经用掉了"，否则券的生命周期在数据层是断的；
2. **种子数据提供样本** —— 基线里有 `used=1` 的订单，测试才有的可断言（见 §5.5）；
3. **简历叙事** —— 「券有领取—核销的完整生命周期」比「只发不销」更像个业务系统。

但要诚实：**如果核销既不实现也不测，它给测试策略带来的增量有限**——能出的是数据完整性断言 + **CAS 幂等断言**（这已经比纯枚举方案多一条：CAS 的行为可以在测试里直接执行两次 UPDATE 验证，不依赖核销接口存在）。真正的动作级迁移用例（核销他人券越权、并发核销）仍然要等核销进到被测范围。

**"改列的语义要迁移存量数据吗？"** 零成本。种子数据走 `docker-entrypoint-initdb.d`，改 SQL 后 `docker compose down -v` 重建即可（见项目 memory：改 SQL 必须 `down -v`）。

---

## 3. 决策二：对账口径简化

`SeckillReconcileTask` 的类注释已经把原则写死了：**订单表是唯一账本，库存是派生值。**

现状（③ 库存重算，`SeckillReconcileTask#reconcileFinishedStocks`）：

```
pending = COUNT(WHERE voucher_id=? AND status=1)      // 在途单守卫
if pending == 0:
    valid = COUNT(WHERE voucher_id=? AND status IN (1,2,3))
    expected = max(0, initial_stock − valid)
```

改造后：

```
expected = max(0, initial_stock − COUNT(WHERE voucher_id=?))
```

**注意 `COUNT(*)` 不筛状态——这不是省事，是语义要求，必须写死：**

> `initial_stock` 是**发放库存**（能领多少张），不是使用库存。券被领走的那一刻就永久占掉一个名额，**核销与否不影响库存**。所以 `used=0（已领取未使用）` 与 `used=1（已使用）` 在库存口径上完全等价，`COUNT(*)` 就是正确口径。

**这条要写进代码注释**，否则将来极可能被"优化"成 `COUNT(WHERE used = 0)`——那样会把已核销的券从账本里剔除，凭空多放出一批库存，直接超卖。恒真的过滤条件难发现，**错误收窄的过滤条件更难发现，因为它看起来"更精确"**。

**在途单守卫整段删除。** 它存在的原因是"活动刚结束时关单仍在发生，此时算出的 expected 下一轮就会被关单改写"——关单没了，账本在活动结束后天然稳定，守卫不再需要。这同时修掉一个现存耦合：**守卫的判定依赖"pending 单最终会迁移出 valid 口径"，而这个迁移动作本身正是被砍掉的关单。**

另两个对账步骤：
- **① 关单兜底**（`closeTimeoutOrders`）：整个方法删除，连带 `ORDER_TIMEOUT_MINUTES` 与分批扫描逻辑。
- **② 补单**（`supplementMissingOrders`）：差集口径是 `COUNT(订单) == SCARD(seckill:order)`，**完全不看状态**，零影响。

---

## 4. 决策三：运维撤回的落点（要不要加第三个状态「已作废」）

状态字段留下来之后，多出一个选项：撤回能不能走一个「已作废」态，而不是物理删除？

**结论：不加，撤回仍走 DELETE。**

理由：撤回是低频运维动作，而 `已作废` 会是一个**在本项目里同样没人写**的第三态——它和「已使用」的关键差别在于，核销是真实业务事件（只是写入方在线下），而「作废」纯属"我们想留个痕迹"的内部诉求。为了留痕迹而造一个无人写入的状态，就是把 §2.5 批评过的死状态再加一遍。审计交给日志，`DELETE` 前后各打一条带 `traceId` 的 WARN 即可。

**落点：DELETE 那一行 + 让对账下一轮（≤60s）把库存重算回来。**

对比现状（`cancelTimeoutOrder` 的 CAS 1→4 + 双半边幂等回补：Redis 半边走 `seckill-restore-stock.lua` 的 SETNX+条件 incr 原子脚本，DB 半边走独立标记 `seckill:restore:db:{orderId}`，整套复杂度全部来自"回补必须 exactly-once，过补会超卖"）：

> 改造后：**回补逻辑从「双半边幂等 + 原子脚本 + 两个 Redis 标记」退化成「删一行」。**

库存是派生值（`SeckillReconcileTask` 类注释原文），删掉账本行后 `expected = initial_stock − COUNT(*)` 自动变大，对账自己会补。不需要 Lua、不需要幂等标记、不需要防超卖论证。

### 4.1 必须写死的边界（否则会挖坑）

**活动期间不能纯 DELETE。** 因为用户还在 `seckill:order` 集合里，对账的②补单会认为"该用户已扣库存但没落单"，于是**再补一单回来**——DELETE 是自愈不了的。

**规格：运维撤回只在活动结束后（券的 `end_time` 已过）开放。** 活动期间的纠偏由运营动作承担（下架券 / 削库存），不提供单笔撤回。理由：

1. 券结束后 `②补单` 与 `③库存重算` 都跑在 `end_time ∈ (now-7d, now-2min)` 窗口内，DELETE 后立即被对账收敛；
2. 出窗（>7d）后对账不再兜底，此时 DELETE 需要**同时**手工改写 Redis 库存——但这类历史清理本就是一次性运维操作，不需要产品化。

---

## 5. 字段级影响清单

### 5.1 `tb_voucher_order` 列变更（`src/main/resources/db/hmdp-schema.sql` 第 137–161 行）

| 动作 | 对象 | 说明 |
| --- | --- | --- |
| 删除列 | `pay_type` | 零代码引用；三方支付不接，概念不存在 |
| 删除列 | `pay_time` | 唯一写入点是 `payOrder`（将删） |
| 删除列 | `refund_time` | 零代码引用 |
| **删除列** | `status` | 两态里「已领取」恒真，枚举列是冗余（见 §2.3） |
| **新增列** | `used` | `tinyint(1) unsigned NOT NULL DEFAULT 0`，是否已使用 |
| **保留** | `use_time` | 注释改为「核销时刻」。**与 `used` 不是冗余**——一个记「是否」，一个记「何时」，是两件不同的信息；组合约束见下 |
| 保留 | `id` / `user_id` / `voucher_id` / `create_time` / `update_time` | — |
| 保留 | `uk_user_voucher(user_id, voucher_id)` | 不动，语义不变（管"领过没有"，不管"用没用过"） |
| **新增** | `CHECK` 约束 `chk_used_consistency` | 见下——锁死 `used` 与 `use_time` 的合法组合 |

#### 为什么 `use_time` 要留（它和 `used` 不是冗余）

`used=1` 能推出「用过了」，但推不出**什么时候用的**。核销时刻是独立的业务信息：客服查证、运营统计核销周期、对账排查都离不开它，而它是**事后补不回来的**——真删了，将来只能靠日志捞。

所以保留两个字段，但它们之间存在约束关系，用 DB 层 CHECK 锁死，而不是靠代码自觉或测试兜底：

```sql
CONSTRAINT `chk_used_consistency` CHECK (
    (`used` = 0 AND `use_time` IS NULL)
 OR (`used` = 1 AND `use_time` IS NOT NULL)
)
```

MySQL 8.0.16+ 起 InnoDB 真正执行 CHECK 约束（本项目镜像是 `mysql:8.0`，满足）。加上它之后：

- 「用了但没时间」「没用却填了时间」这两种脏数据在写入时就被拒绝，**根本存不进去**；
- §5.5 的自洽约束从「测试要断言的不变量」升级成「数据库保证的事实」——测试改为验证约束存在且生效（插一条违规数据，期望报错），比逐行断言更强；
- 代码里不需要任何一致性校验逻辑，CAS 那句 UPDATE 天然满足它。

> 如果确实认为核销时间对本项目无用，删掉 `use_time` 也能跑——`used` 单独就够表达两态。但这是**信息丢失**，不是简化，需要明确确认。

### 5.2 索引变更

| 索引 | 现状用途（schema 注释原文） | 动作 |
| --- | --- | --- |
| `idx_status_create_time(status, create_time)` | 「①关单兜底扫描（`WHERE status=1 AND create_time<?`）」 | **删除**。关单没了，这是纯写放大 |
| `idx_voucher_status(voucher_id, status)` | 「②补单（`WHERE voucher_id=?`）与③库存重算（`WHERE voucher_id=? AND status IN (1,2)`）共用——后者用到完整两列，前者只用前缀」 | **降为 `idx_voucher(voucher_id)`**。改造后③不筛 status（见 §3），②只用前缀 → status 列在索引里已无查询服务。将来要统计核销率再加回 |
| `uk_user_voucher(user_id, voucher_id)` | 一人一单 | 不动 |

### 5.3 Java 常量与枚举

| 文件 | 对象 | 动作 |
| --- | --- | --- |
| `utils/RedisConstants.java:61-66` | `ORDER_STATUS_UNPAID` / `PAID` / `VERIFIED` / `CANCELLED` 四个常量 + 状态注释块 | **全部删除，不新增常量**。布尔值不需要枚举常量——DB 默认值与 CAS 的 WHERE 条件直接写字面量 `0`/`1`，加了常量反而会诱使人重新引入枚举语义 |
| `utils/RedisConstants.java` | `SECKILL_RESTORE_DB_KEY`、`SECKILL_RESTORE_TTL_SECONDS` | **删除**（回补链路整体删除） |
| `utils/RocketMQConstants.java:27` | `ORDER_TIMEOUT_DELAY_LEVEL = 15` | **删除** |
| `utils/SeckillMode.java` | `QUEUE_*` 七个常量 | **保留**（见 §7，这是新的状态迁移测试载体） |
| `observability/SeckillMetrics.java:24,67-69` | `ORDER_TIMEOUT_SEND_ERROR` 指标 + `orderTimeoutSendError()` 方法 | **删除** |
| `observability/SeckillMetrics.java` | `Reason` 枚举 | **不动**。十个取值里没有一个是支付/关单相关的 |

### 5.4 方法与 API

| 位置 | 对象 | 动作 |
| --- | --- | --- |
| `VoucherOrderServiceImpl#payOrder` | 模拟支付（CAS 1→2 带 user_id 防越权） | **删除** |
| `VoucherOrderController#payOrder` | `PUT /voucher-order/pay/{id}` | **删除** |
| `VoucherOrderServiceImpl#cancelTimeoutOrder` | 取消 + 双半边回补 | **删除** |
| `IVoucherOrderService#cancelTimeoutOrder` | 接口声明 | **删除** |
| `VoucherOrderServiceImpl#createOrderFromMQ` | 事务内 `setStatus(ORDER_STATUS_UNPAID)` | **删除该行**。`used` 靠 DB 默认 `0`，落库不写这一列 |
| `VoucherOrderServiceImpl#createOrderFromMQ` | 事务提交后 `rocketMQProducer.sendOrderTimeout(orderId)` | **删除**（含 try/catch 与 `seckillMetrics.orderTimeoutSendError()`） |
| `RocketMQProducer#sendOrderTimeout` | 延迟关单消息发送 | **删除** |
| `OrderMQConsumer` | 订阅 `"CREATE \|\| TIMEOUT"`、TIMEOUT 分支、`ORDER_TAG_TIMEOUT` 处理 | **订阅改为 `"CREATE"`，删 TIMEOUT 分支** |
| `SeckillReconcileTask#closeTimeoutOrders` | ①关单兜底（分批扫描） | **删除**（连带 `ORDER_TIMEOUT_MINUTES`、`CLOSE_SCAN_BATCH_SIZE`） |
| `SeckillReconcileTask#reconcile` | `runStep("关单", ...)` | **删除该行** |
| `SeckillReconcileTask#reconcileFinishedStocks` | 在途单守卫 + `status IN (1,2,3)` | **守卫删除，valid 改为 `COUNT(*)`** |
| `VoucherOrderServiceImpl#getSeckillResult` | `data.put("orderStatus", order.getStatus())` | **改为 `data.put("used", order.getUsed())`**，返回 `0` / `1` |
| `VoucherOrder` entity | `payType` / `payTime` / `refundTime` / `status` 四个字段 | **删除** |
| `VoucherOrder` entity | `useTime` | **保留**，注释改为核销时刻 |
| `VoucherOrder` entity | — | **新增 `used`**（`Integer`，与 `status` 同为 `tinyint` 的风格保持一致） |
| `src/main/resources/seckill-restore-stock.lua` | 回补 Redis 库存原子脚本 | **删除文件** |
| `VoucherOrderServiceImpl` | `RESTORE_STOCK_SCRIPT` 静态块与字段 | **删除** |

### 5.5 种子数据（`src/main/resources/db/hmdp-seed-data.sql` 第 112–124 行）

现状 9 条订单的 status 分布：**1 条 status=1**（未支付）、**4 条 status=2**（已支付）、**4 条 status=3**（已核销）；`pay_type` 取 1/2/3；`pay_time` 7 条非空、`use_time` 4 条非空。

**处理：改种子数据，不搞"自然兼容"。**

具体：INSERT 列清单去掉 `pay_type` / `pay_time` / `refund_time` / `status` 四列；`used` 与 `use_time` 按下表填，**`used=0` 的行不写 `used` 列**（走 DB 默认值，顺便验证默认值生效）。注释块（第 103–105 行"未支付/已支付/已核销各 1"）同步改写。

**为什么不自然兼容**：`status=2` 在旧语义下是"已支付"，在新语义下是"已使用"——同一批数字含义完全不同，留着等于让一个已删除的概念（支付）在测试基线上继续活着。

**新的分布设计（9 条，覆盖两种状态 × 三种券态）：**

| 券 | 状态 | 条数 | `used` | `use_time` |
| --- | --- | --- | --- | --- |
| 券 12（已结束秒杀，8-26） | 活动早已结束 | 3 | `1` | 非空，在活动结束后数天内 |
| 券 12 | 同上 | 1 | `0` | NULL —— **领了没去用**，这是真实且值得测的分支 |
| 券 10（进行中秒杀） | 活动进行中 | 2 | `0` | NULL |
| 券 10 | 活动进行中 | 1 | `1` | 非空，领用当天 |
| 券 1（普通券，非秒杀） | 无窗口 | 1 | `0` | NULL |
| 券 1 | 无窗口 | 1 | `1` | 非空 |

合计：`used=0` × 4、`used=1` × 5。**领券落库的 4 条不要写 `used` 列**，让它走 DB 默认 `0`——种子数据同时也是「默认值生效」的验证样本。三条必须成立的自洽约束：

1. `used = 1` ⟺ `use_time IS NOT NULL` —— **已由 §5.1 的 CHECK 约束在 DB 层强制**，种子数据违反会直接导入失败
2. `use_time > create_time`（核销必然晚于领取）—— 仍是可断言的不变量
3. 券 12 那三笔已使用的 `use_time` 落在其 `end_time` 之后 —— 仍是可断言的不变量

**与初版判断的差异说明**：初版认为 `use_time` 是虚构的所以要删——依据是"核销流程从未实现，没有任何代码会写它"。**现在依据变了**：核销是真实业务事件（只是写入方线下），`use_time` 是它的证据字段，捏造它**有业务语义支撑**；而 `pay_time` 对应的支付事件已被明确砍掉，删它没商量。两者不是同一回事。

---

## 6. 决策四：术语对齐

### 6.1 对外叫什么

**「领券」。** 优惠券免费领，不含支付，业务语义就是这个。

但**「抢」这个字保留在口述与简历里**——券是限时限量、有并发洪峰的，用户感知就是抢，而且它是并发正确性的叙事入口。两者不矛盾，是同一条链路的两种说法：

| 场合 | 用词 | 理由 |
| --- | --- | --- |
| 接口 / 文档 / 测试用例命名 | **领券** | 业务语义准确，不含支付暗示 |
| 简历与面试口述 | **抢券**（必要时补一句"就是领券，限时限量所以叫抢"） | 带出并发与正确性 |
| 代码标识符 | **保留 `seckill`** | 见下 |

### 6.2 `seckill` 改名范围实测

| 范围 | 数量 | 说明 |
| --- | --- | --- |
| `src/main/java` 含 seckill 的文件 | **29 / 123** | |
| `src/main/java` 标识符出现次数 | **339** | 含类名、方法名、常量名、Redis key 前缀、指标名 |
| `src/main/resources` | 23 | 4 个 lua + yaml + SQL 注释 |
| `docs/` + `README.md` | 80 | |
| `src/main/java` 注释里"秒杀"字样 | 55 | |

### 6.3 判断：不全量改名，只改"人读的那一层"

**改名范围 = 人读的地方全面改用「领券」（README / docs / 测试用例 / 简历稿，约 80+55 处）；代码标识符、Redis key、指标名保留 `seckill`，并在术语表写明映射。**

四条理由：

1. **Redis key 前缀是硬成本，不是 rename 能解决的。** `seckill:stock:{id}`、`seckill:order:{id}`、`seckill:claim:{id}`、`seckill:txn:{id}` —— 改名等于**换 key 空间**，而这套 key 的生命周期是被对账任务管着的（`KEY_TTL_AFTER_END_SECONDS = 14d`，出窗才自然死亡）。存量 key 全部失效，且 `seckill.lua` 里明确写了「stockKey/orderKey 绝不能加 TTL——key 过期后 warmUp 的 beginMillis 守卫会 fail-closed 拒绝回填，活动将永久库存不足」。这是实打实的迁移风险。

2. **指标名改了会断面板。** `hmdp.seckill.result` / `hmdp.seckill.latency` / `hmdp.seckill.degraded` 对应 `docker/grafana/hmdp-seckill.json` 的 8 个面板；`SeckillMode.A` 的注释里也写了「保留 A 是因为它同时是指标的 mode tag 值，去掉会让历史面板断档」。同一条理由在这里同样成立。

3. **"秒杀"是这条链路的技术特征，不是业务名。** 券限时限量、有洪峰、走 Lua 原子预扣——这就是秒杀的技术定义。对外讲"校园餐饮优惠平台的领券"，对内讲"这条领券链路用秒杀的技法实现"，**叙事上是加分**：说明这套技法是可迁移的，不是只会照抄一个秒杀 Demo。

4. **投入产出比最高的那部分恰恰是最便宜的。** docs 那 80 处是给人读的、是求职材料，满篇"秒杀"会让人以为做的还是那个烂大街的秒杀项目——改它零风险。

### 6.4 落地要求

- 在 `README.md` 加一节**术语表**：`领券（对外）= seckill 代码实现（对内）`，并说明 Redis key 前缀沿用 `seckill:` 的历史原因。
- 接口 URL `POST /voucher-order/seckill/{id}` 与 `GET /voucher-order/seckill/result/{orderId}` **保留不动**（前端 `shop-dianping-frontend/html/hmdp/shop-detail.html` 只有 1 处调用）。契约变更属"实际写代码"范畴，在 map 外，且留着不构成叙事负担。
- 前端 `shop-detail.html` 的 `// 秒杀抢购` 注释与"抢购成功，订单id：..."提示语改为"领券"——这是用户能看见的字，值得改。

---

## 7. 附带发现：状态迁移测试有两个载体，别只盯一个

map 的能力侧重里列了「状态迁移」测试法。改造后它有两个载体，各有各的用法。

### 7.1 载体一：`SeckillMode.QUEUE_*`（异步落库排队状态）

由入口与消费者分两头写，定义 7 个状态：

| 状态 | 写入点 | 实测是否可达 |
| --- | --- | --- |
| `WAITING` | 入口 `seckillVoucher`（Lua 返回 0 后） | ✅ `VoucherOrderServiceImpl:498` |
| `SUCCESS` | 消费者落库成功 / 查订单表命中 | ✅ `:369`、`:389`、`:586` |
| `FAIL_STOCK` | 消费者发现 DB 库存不足 | ✅ `:374` |
| `NOT_FOUND` | 查无此单时回写的空值标记 | ✅ `:593`、`:614` |
| `UNKNOWN` | 排队状态缺失 + 订单表查不动（DB 故障） | ✅ `:582`（只返回不落盘） |
| `FAIL_REPEAT` | — | ❌ **定义了但从未写入** |
| `FAIL_SYSTEM` | — | ❌ **定义了但从未写入** |

（核实方式：`grep -rn "QUEUE_" src/main/java`，排除 `SeckillMode.java` 自身，只有上表列出的 8 个写入/返回点。）

它适合出状态迁移用例，理由三条：

1. **它是真的异步**——入口写 WAITING，消费者在另一个线程、可能几十秒后写终态，中途可以被 DB 熔断打断，天然有多条路径；
2. **它有边界态**——`UNKNOWN`（暂时不知道，让前端继续轮询）与 `NOT_FOUND`（确定没有）的区别是混沌测试挖出来的真实设计，值得单独出用例；
3. **它有两个死状态**——`FAIL_REPEAT` / `FAIL_SYSTEM` 定义了却没有任何代码写入。**这是现成的测试发现点**：要么补写入路径，要么删掉常量。测试策略票里应该出一条"遍历所有定义的排队状态，验证可达性"的用例。

### 7.2 载体二：`used`（券的生命周期：已领取 → 已使用）

两态一迁移，但**写入方在线下（见 §10）**，所以本轮能出的用例是**数据完整性断言**而不是迁移动作测试：

| 用例 | 断言 |
| --- | --- |
| 领券落库后状态正确 | 新订单 `used = 0` 且 `use_time IS NULL`（**注意验的是 DB 默认值生效**，落库 SQL 不该出现这一列） |
| **一致性由 DB 强制**（替代逐行断言） | 插一条 `used=1, use_time=NULL` 的违规数据，**期望写入被拒绝**。这比"遍历所有行检查自洽"更强——它证明的是约束存在且生效，而不是当前数据碰巧没问题 |
| 时序不变量 | 所有 `used = 1` 的行满足 `use_time > create_time` |
| **核销 CAS 幂等** | 对同一 `orderId` 连续两次执行核销 UPDATE：首次影响 1 行，第二次影响 0 行，且 `use_time` **不被第二次覆盖** |
| 状态不被领券链路篡改 | 领券（含 MQ 重投 / 对账补单）不会把 `used` 从 1 改回 0 |

**要诚实说明**：没有核销接口，就测不了"核销这个动作"（重复核销幂等、核销他人券的越权、核销已使用券的拒绝）。这几条是真状态迁移用例的精华，**它们要等核销进入被测范围才有**。把它们记进 fog，不要假装已经有了。

---

## 8. 对下游票的输入

| 下游票 | 本文给它的输入 |
| --- | --- |
| 关单消失后，定时对账任务的新职责是什么 | ①关单删除；②补单不变；③库存重算退化为 `initial_stock − COUNT(*)`（**不筛状态**，理由见 §3），在途单守卫删除。对账从「三步」变「两步」，且不再依赖 `status` 索引 |
| 抢券链路测试策略：并发正确性如何证明 | 状态迁移用例有两个载体：`QUEUE_*` 排队状态机（7 状态含 2 个死状态）＋ `used` 布尔（只能出数据完整性断言 + CAS 幂等断言）。一人一单断言直接查 `uk_user_voucher`，与 `used` 正交 |
| 删除清单：支付域与关单域的代码、DDL 与文档边界 | 本文 §5 是第一批：**删列 4 个**（`pay_type`/`pay_time`/`refund_time`/`status`）、**新增列 1 个**（`used`）、**保留 1 个**（`use_time`）、2 个索引调整、1 个 CHECK 约束、约 15 处方法/常量删除、1 个 lua 文件 |

## 9. 不在本文范围内

- 核销 / 退款**接口的设计与实现** —— 不做、不测（见 §10 范围声明）。`used=1` 只作为数据模型与种子样本存在。
- 三方支付 —— 明确不接。`pay_type`/`pay_time` 照删，状态机里不设「已支付」。
- 实际改代码、跑 DDL、提 PR —— map 内只产规格。
- 缓存链路与登录链路的状态语义 —— 与订单表无关。

---

## 10. 范围声明：谁写 `used = 1`（不可省略）

§2.5 论证过「已使用」不是死状态，但那条辩护的前提就是本节的结论。**没有这一节，「已使用」和 `FAIL_REPEAT` 没有区别。**

**怡霖 2026-09-05 明确的范围边界：**

> 不是外部接口，我只管**查询商铺**和**领取优惠券**两个接口。

据此定死三件事：

| 项 | 结论 |
| --- | --- |
| 本项目提供哪些订单相关接口 | `POST /voucher-order/seckill/{id}`（领券）、`GET /voucher-order/seckill/result/{orderId}`（查询落库结果）。**不提供核销接口** |
| 被测范围 | 查询商铺 / 领取优惠券。**核销不在三条被测链路内** |
| `used = 1` 由谁写入 | **商家端 / 线下核销动作**，一句 CAS：`UPDATE tb_voucher_order SET used=1, use_time=NOW() WHERE id=? AND used=0`。本项目不提供这个写入路径，只保证：① 数据模型能表达它 ② 种子数据有样本 ③ 对账口径不因它出错 ④ CHECK 约束兜住 `used` 与 `use_time` 的组合 |

**由此产生的四条约束（必须写进代码/文档，否则将来会被误改）：**

1. **领券链路根本不写 `used` 列**，靠 DB 默认 `0`；任何路径（含 MQ 重投、对账补单）都不得把 `used` 从 1 改回 0。
2. **对账的 `COUNT(*)` 不筛状态**（§3），防止有人误以为"已核销的不占库存"。
3. **核销必须是 CAS 而不是先查后写**（§2.3），否则重复核销会覆盖 `use_time`。
4. **撤回走 DELETE 不加「已作废」态**（§4）——本项目不为内部审计诉求造无人写入的状态。

**留给 fog**：核销接口若将来进入被测范围，状态迁移测试才有真正的动作级用例（重复核销幂等、核销他人券越权、核销已使用券拒绝）。届时需重新评估本节。
