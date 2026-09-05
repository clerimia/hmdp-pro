# 对账任务的观测口径：指标契约与断言口径

> wayfinder 票：**对账任务的观测口径：补单与库存重算怎么被断言**
> 所属 map：hmdp-pro 转型校园餐饮优惠平台 · 三条链路改造规格与测试策略
> 日期：2026-09-05
> 上游：`docs/plans/2026-09-05-reconcile-task-new-duty.md`（§7.1 的四个候选指标）
> 下游：`docs/plans/2026-09-05-pytest-framework-structure.md`（§4.4 指标断言助手）
> 本文所有对现有代码的说法均已对着 `src/main/java` 与 `src/main/resources` 核实。
> 本文是**契约规格**，不含实现。

---

## 0. 结论（TL;DR）

| 问题 | 结论 |
| --- | --- |
| 加哪些指标？ | **4 个，全是 Counter**：`round` / `step` / `supplement` / `restock`。上游候选的 `drift` Gauge **砍掉**。 |
| 放哪个类？ | **新建 `ReconcileMetrics`**，不塞进 `SeckillMetrics`（理由见 §5）。 |
| tag 红线 | `voucherId` / `shopId` / `userId` / `orderId` / `traceId` **一律禁止**，写进 `ObservabilityRecorder#increment` 的 javadoc（唯一收口处）+ 新建类的类注释 + `docs/observability.md` §3。 |
| 步骤级指标要不要？ | **要**。`hmdp.reconcile.step{step,result=ok\|error}` 是「对账压根没跑 / 某步异常被 `runStep` 吞了」的唯一出口。 |
| 算不算写代码？ | **不算**。本票只定契约（指标名 / tag 合法取值 / 计数单位 / 埋点分支 / 断言口径），实现留给执行阶段。 |
| 对账收敛怎么断言？ | **指标只断言「任务层」，券层归 DB/Redis 断言**，且断言前必须做**窗口隔离**。见 §4。 |

---

## 1. 判据：先回答「测试真正要断言什么」

上游给了四个候选，问「四个全要还是砍到最小集」。判据不是「信息量越大越好」，而是**逐个问：这条信息是否只有指标能给，DB/Redis 断言给不了？**

| 候选想回答的问题 | pytest 用 DB/Redis 断言能回答吗 | 结论 |
| --- | --- | --- |
| 对账这一轮跑没跑 | **不能**。DB/Redis 里看不出定时任务有没有执行 | 要 |
| 某一步是不是抛异常被吞了 | **不能**。`runStep` 只 `log.error`，无任何外部出口 | 要 |
| 补单发生了吗、几笔 | 部分能（`order_count` 恢复已经很强），但**发送失败**那一侧只能靠指标 | 要 |
| 库存被改写了吗 | 能（`assert_stock` + `assert_stock_key`），但**漂移告警**需要一个能长期盯的口径 | 要（改成告警口径，不是断言口径） |
| Redis 与 DB 当前差多少 | **能，而且更准**（精确到某一张券） | **砍掉** |

**一句话**：指标在对账这里回答的是「**任务层**发生了什么」；「**某一张券**现在是什么状态」归 DB/Redis 断言。这个分工是由 tag 红线逼出来的——见 §3。

---

## 2. 指标契约

### 2.1 四个指标

命名一律点分、不带 `_total` 后缀（Micrometer 会自动加，见 §6.1）。

| 指标（点分名） | Prometheus 名 | 类型 | tag 合法值 | 计数单位（**语义**） | 埋点分支 |
| --- | --- | --- | --- | --- | --- |
| `hmdp.reconcile.round` | `hmdp_reconcile_round_total` | Counter | `outcome=completed\|skipped_supplement\|skipped_lock` | **轮次**。每轮调度**至多 +1**，是任务心跳 | `reconcile()`：`tryLock` 失败 → `skipped_lock`；补单结果为 `true`（补过单）或 `null`（补单抛异常，见 §7）→ `skipped_supplement`；两步都执行 → `completed` |
| `hmdp.reconcile.step` | `hmdp_reconcile_step_total` | Counter | `step=supplement\|restock`，`result=ok\|error` | **步骤级成败**。`error` = `runStep` 吞掉的那个异常 | `reconcile()` 内，取代现在 `runStep` 里的裸 `log.error` |
| `hmdp.reconcile.supplement` | `hmdp_reconcile_supplement_total` | Counter | `result=ok\|error` | **补发动作次数**（⚠️ 不是丢单笔数，见 §6.4） | `supplementMissingOrders()`：`sendOrderCreate` 成功 → `ok`；`catch` → `error` |
| `hmdp.reconcile.restock` | `hmdp_reconcile_restock_total` | Counter | `result=adjusted\|converged` | **券次判定**。每轮每张在窗券 +1，一次不多一次不少 | `reconcileFinishedStocks()`：进 `if (db != expected \|\| redis != expected)` → `adjusted`；`else` → `converged`（**该 else 分支当前不存在，执行阶段要补**） |

### 2.2 Java 侧门面签名（执行阶段照此实现）

```java
@Component
public class ReconcileMetrics {

    public static final String RECONCILE_ROUND = "hmdp.reconcile.round";
    public static final String RECONCILE_STEP = "hmdp.reconcile.step";
    public static final String RECONCILE_SUPPLEMENT = "hmdp.reconcile.supplement";
    public static final String RECONCILE_RESTOCK = "hmdp.reconcile.restock";

    public void round(String outcome);          // completed / skipped_supplement / skipped_lock
    public void step(String step, boolean ok);  // supplement / restock，ok=false 即 result=error
    public void supplement(boolean ok);         // 一笔补发动作
    public void restock(boolean adjusted);      // 一张券一次的判定结果
}
```

### 2.3 刻意不埋点的分支（写下来，免得将来被当成遗漏）

判据同上——测试不断言、且已有 `log.warn` 承载，就不进指标：

| 分支 | 处置 |
| --- | --- |
| `SCARD == 0`（EARLY 档不写集合） | 稳态 `continue`，不埋点 |
| `COUNT == SCARD`（无丢单） | 稳态 `continue`，不埋点 |
| `COUNT > SCARD`（上游 §2.4 的档位切换/集合被清理） | 保留 `log.warn`，不埋点 |
| `initialStock == null \|\| <= 0` | 保留 `log.warn`，**不计 `restock`**（数据缺陷，不是判定结果） |
| `seckill:claim` 映射缺失回退新 orderId | 保留 `log.warn`，不埋点 |

---

## 3. tag 红线：为什么 `voucherId` 不能进（以及它逼出的断言分工）

`voucherId` 是有限枚举吗？不是——**每上一张券就多一个值**，和 `orderId` / `userId` 是同一类高基数陷阱。每个唯一 tag 组合在 Prometheus 里是一条独立时间序列，会把采集端内存和查询一起打爆。

**红线要写在三个地方**（缺一处就会被人为了排查方便加回去）：

1. **`ObservabilityRecorder#increment` 的 javadoc** —— 这是所有埋点的**唯一收口处**（`SeckillMetrics` / `CacheMetrics` / `ResilienceMetrics` / 新建的 `ReconcileMetrics` 全部经它），写在别处都有漏网的可能。现有文案只点了 `orderId / userId / traceId`，**要补 `voucherId / shopId`**。
2. **新建 `ReconcileMetrics` 的类注释** —— 写清「对账最容易想加的就是 `voucherId`，因为日志排查时最想要的就是它；它只能进日志」。
3. **`docs/observability.md` §3「tag 使用红线」** —— 同步补 `voucherId / shopId`。

**这条红线直接决定了断言分工**：对账指标全是**全局标量**，`delta` 里混着这一轮扫到的**所有**在窗券。所以——

> 要把一个 delta 归到某一张券，测试必须先做**窗口隔离**（§4.1），否则指标只能用来断言「任务层」。

---

## 4. 断言口径：测试如何断言「对账收敛」

### 4.1 两条前提（不遵守必 flaky）

**前提一：测试控制不了对账的执行时刻，只能控制它的输入。**

- 触发条件（改造后）：`endTime ∈ [now − 7d, now − 8min)`
- 调度：`@Scheduled(fixedDelay = 60_000)`
- 所以测试的动作是：**把被测券的 `end_time` 回拨到 `now − 9min`**，然后等下一个调度周期。
- 所有等待一律 `wait_until(timeout=90)`（60s 调度间隔 + 30s 执行余量）。**禁止 `sleep(60)`** —— CI 上慢一点就 flaky，快一点又白等。

> 顺带说一句：`RECONCILE_AFTER_END_MINUTES` 从 2 改到 8（上游 §6.3）对测试是**净收益**。守卫越大，回拨后「在途重试消息被误判成丢单」的窗口越小，`supplement` 计数越干净。

**前提二：指标断言前必须先做窗口隔离。**

对账扫的是**所有**满足窗口条件的券。种子数据里那 4 张券的窗口是容器首次启动时按 `NOW()` 算的相对偏移，**跑久了会漂进 `(now−7d, now−8min)`**，跟测试券一起被扫——`supplement` / `restock` 的 delta 就被污染了。

```python
@pytest.fixture
def reconcile_window_isolated(db):
    """把窗口内除被测券外的券全部推出窗口（end_time → now+1d），teardown 还原。"""
```

这个 fixture 不是可选优化，是**断言有效性的前提**。

### 4.2 三条断言链

**链一：丢单 → 补单 → 账本补齐**

```python
# 1. 造券（stock=100，begin=now-10min，end=now+60min），N 个用户秒杀成功
assert_order_count(db, vid, N, timeout=10)                  # 证明 N 条消息全部落库，无在途
assert redis.scard(f"seckill:order:{vid}") == N

# 2. 注入丢单：删账本，保留集合与 claim 映射
db.execute("DELETE FROM tb_voucher_order WHERE voucher_id=%s", vid)
#   → COUNT=0，SCARD=N，seckill:claim:{vid} 完好  ⟸ 这就是丢单态，
#     且补单会复用原 orderId（seckill.lua:37 写的映射还在）

# 3. 回拨窗口，让这张券进入对账范围
db.execute("UPDATE tb_seckill_voucher SET end_time = NOW() - INTERVAL 9 MINUTE "
           "WHERE voucher_id=%s", vid)

# 4. 等补单（指标：任务层）
wait_until(lambda: snap.delta("hmdp.reconcile.supplement", {"result": "ok"}) >= N,
           timeout=90, desc="对账补单")

# 5. 等账本补齐（DB 断言：券层）
wait_until(lambda: order_count(db, vid) == N, timeout=30)

# 6. 补单这一步没有吞异常
snap.delta_eq("hmdp.reconcile.step", {"step": "supplement", "result": "error"}, 0)
```

**为什么用「删订单行」而不是「停 broker」注入丢单**：停 broker 不可控（事务消息回查 `hasSeckillTxnMarker` 会把半消息捞回来，只有连存储卷一起删才真丢），且属于混沌/非功能范畴。删行是**状态注入**，确定性高、teardown 干净，正好落在 pytest 接口自动化的能力范围内。

**链二：账本补齐后 → 库存重算 → 收敛**

```python
# 7. 等下一轮走完两步
wait_until(lambda: snap.delta("hmdp.reconcile.round", {"outcome": "completed"}) >= 1,
           timeout=90)

# 8. 库存收敛（DB + Redis 双断言）
assert_stock(db, vid, 100 - N, timeout=30)          # 不筛 used
assert_stock_key(redis, vid, 100 - N)

# 9. 重算这一步没有吞异常
snap.delta_eq("hmdp.reconcile.step", {"step": "restock", "result": "error"}, 0)
```

**链三：核销不影响库存（上游 §3.1 的回归测试，必须有）**

```python
# 10. 核销一笔
db.execute("UPDATE tb_voucher_order SET used=1, use_time=NOW() "
           "WHERE voucher_id=%s LIMIT 1", vid)

# 11. 再等一轮
wait_until(lambda: snap.delta("hmdp.reconcile.round", {"outcome": "completed"}) >= 2,
           timeout=120)

# 12. ★ 库存必须不变
assert_stock(db, vid, 100 - N)
assert_stock_key(redis, vid, 100 - N)
```

> 这条是整个对账口径里**最容易被人改坏的一处**。
> 如果有人把 `COUNT(*)` 「优化」成 `COUNT(WHERE used = 0)`，第 12 步的期望值会从 `100 − N` 变成 `100 − N + 1`，用例立刻失败。
> 恒真的过滤条件难以发现，**错误收窄的过滤条件更难发现，因为它看起来「更精确」**——必须有一条用例钉住。

**链四（漂移告警，不是收敛断言）**

```python
# 13. 故意制造漂移
redis.set(f"seckill:stock:{vid}", "999")
wait_until(lambda: snap.delta("hmdp.reconcile.restock", {"result": "adjusted"}) >= 1,
           timeout=90)
assert_stock_key(redis, vid, 100 - N)      # 被改回来
```

### 4.3 口径红线（写进断言助手的注释）

| 红线 | 原因 |
| --- | --- |
| **收敛用例禁止断言 `restock{result="adjusted"} > 0`** | 收敛态本来就该是 0。断言它等于写「期待漂移」。只有漂移用例（链四）才断言它。 |
| **禁止 `sleep` 等对账** | 一律 `wait_until`。 |
| **对账指标断言前必须 `reconcile_window_isolated`** | 否则 delta 混入其他券，见 §4.1 前提二。 |
| **`metrics.py` 的 delta 必须把「序列不存在」当 0** | Micrometer Counter 首次打点前指标根本不在 `/actuator/prometheus` 里。`delta_eq(..., 0)` 和 `assert_no_increase` 全靠这条——它是 `common/metrics.py` 的一条硬约束，pytest 框架规格里还没写，执行阶段要补。 |

---

## 5. 为什么新建 `ReconcileMetrics` 而不是塞进 `SeckillMetrics`

上下游票都说「塞进 `SeckillMetrics`」，核实后**否决**，三条理由：

1. **对账不在请求路径上。** `SeckillMetrics` 的类注释写的是「秒杀链路的关键事件门面」——QPS、成功率、耗时、降级，全是**请求级**指标，生命周期跟请求走。对账是分钟级的定时任务，塞进去会让一张面板里混进两种完全不同时间尺度的东西。
2. **删除窗口重叠。** 关单域删除时要动 `SeckillMetrics`（删 `ORDER_TIMEOUT_SEND_ERROR` 常量 + 方法 + 注释，见 `SeckillMetrics:24/67-70`）。同一批改动里一边删一边加对账指标，diff 难审。分成两个文件，删除清单那张票的改动面就纯粹了。
3. **与 `CacheMetrics` 同构。** 项目已有先例：「商铺缓存不属于秒杀链路，各自维护指标口径」（`CacheMetrics:6-7`）。对账同理，照抄这个模式即可，不引入新概念。

`docs/observability.md` §3 的指标表和扩展点④要同步加一行 `ReconcileMetrics`。

---

## 6. 对上游 §7.1 的四处修订

### 6.1 🔴 命名：`_total` 后缀会导致双后缀

上游写的三个名字是 `hmdp.reconcile.supplement_total` / `restock_adjusted_total` / `round_total`。

**核实**：本项目的转换规则是点分名 → Micrometer 自动加 `_total`（`SeckillMetrics:11-14` 的类注释、`docs/observability.md` §3 都写明了）。所以 `hmdp.reconcile.supplement_total` 在 Prometheus 侧会变成 **`hmdp_reconcile_supplement_total_total`**——和类注释里警告的「Timer 名别自己加 `_seconds`，否则变成 `..._seconds_seconds`」是同一个坑。

**修订：全部去掉 `_total` 后缀。**

### 6.2 🔴 `drift` Gauge 砍掉

四条理由，按强度排序：

1. **项目根本没有 Gauge 原语。** `ObservabilityRecorder` 只有 `increment` / `startTimer` / `stopTimer` 三个方法（已核实接口全文），`MicrometerRecorder` 只实现了 Counter 和 Timer。全项目唯一出现的 Gauge 是 `resilience4j_circuitbreaker_state`——那是 R4J 库自己注册的，不是我们的。加 `drift` 要动 **接口 + `MicrometerRecorder` + `NoOpRecorder` + 文档**，是四个候选里成本最高的一个。
2. **语义模糊。** Gauge 是抓取时求值的瞬时量。对账 60s 跑一轮，Prometheus 15s 抓一次——四次里有三次读到的是上一轮的值，你不知道自己看的是第几轮的结论。
3. **它想回答的问题，DB/Redis 断言答得更准。** pytest 直接 `assert_stock(db, vid) == assert_stock_key(redis, vid)`，精确到某一张券；而 Gauge 因为 `voucherId` 红线只能给一个无 tag 的聚合值（全窗口求和的话，`+3` 和 `−3` 还会互相抵消成 0）。
4. **告警需求已被 `restock` 覆盖。** 「漂移发生过」= `increase(hmdp_reconcile_restock_total{result="adjusted"}[1h]) > 0`，与死信告警完全同构（`docs/observability.md` §7.4 那条 `or vector(0)` 规则照抄即可）。

### 6.3 `round_total` 的 outcome 只有 2 个，不够

上游给的是 `outcome=skipped_supplement / converged`。**修订为 `completed / skipped_supplement / skipped_lock`**：

- `converged` 这个名字是错的——**轮次**没有「收敛」这个属性，收敛是**券**的属性（已经移到 `restock{result=converged}` 上了）。
- 缺 `skipped_lock`：`tryLock(0, 50s)` 抢不到锁时直接 `return`，**整个方法不打任何日志**。多实例部署下「对账没跑」和「对账跑了但没事发生」会完全混在一起。

### 6.4 🟡 `supplement` 是「补发动作次数」，不是「丢单笔数」

上游说 `supplement_total` 是「补单条数 = 丢单率的唯一量化口径」。**这个说法不准确。**

补单是异步的：`sendOrderCreate` 成功即计数，但订单要等消费者落库才进 `tb_voucher_order`。**下一轮（60s 后）如果还没落库，差集里还有这个 userId，会再补发一次，再计一次数。**

所以：

- `supplement{result=ok}` = **补发动作次数**，同一笔丢单在收敛前会被重复计数。
- 真正的「丢单笔数」= **每轮的差集大小**，那是个**瞬时量**。

**契约里必须写死这个语义**，否则 Grafana 面板会把「重试 3 次才成功」画成「丢了 3 单」，直接得出错误的丢单率。

丢单笔数的正确载体是**日志**（`log.warn` 已经带 `voucherId` 和 `userId`，`SeckillReconcileTask:215`）。将来若真要它的时序，正确做法是一个无 tag 的 **Gauge 记录「本轮扫描到的最大差集」**——本票不做（无 Gauge 原语，且测试用不到）。

---

## 7. 附带发现：🔴 `runStep` 吞异常会连带你跳过「补过单就跳过重算」的守卫

对着 `SeckillReconcileTask:88-95` 核实：

```java
Boolean supplemented = runStep("补单", this::supplementMissingOrders);   // 异常 → 返回 null
if (Boolean.TRUE.equals(supplemented)) {
    log.warn("对账：本轮发生补单，跳过库存重算，下轮再算");
} else {
    runStep("库存重算", this::reconcileFinishedStocks);                  // ← null 也走这里
}
```

**`runStep(String, Supplier<T>)` 捕获异常后返回 `null`（`:112-119`），而 `Boolean.TRUE.equals(null) == false`，于是补单抛异常时库存重算照常执行。**

后果：如果补单是**半途**炸的（已经补发出去几笔、消息还在飞、尚未落库），`COUNT(*)` 就比真实账本少这几笔 → `expected = initial − COUNT` **算大** → **多放库存**。

这正是上游 §5 强调「顺序不可换，先算就会算大（多放库存）」要防的事，也是 `chaos-test-report.md` §3.9 实测修复过的那类问题——**守卫只堵了「补单成功」这一侧，没堵「补单失败」那一侧。**

**契约修订（本票决定，执行阶段实现）**：

1. `supplementMissingOrders` 的返回类型从 `boolean` 改成 `Boolean`（三态：`TRUE` 补过单 / `FALSE` 没补 / `null` 异常），或者用一个显式的 `StepResult` 枚举——**推荐枚举**，因为 `Boolean` 三态的语义完全靠注释维系，和 §6.4 里「恒真的条件难发现」是同一类隐患。
2. **补单结果为「补过单」或「异常」时，本轮都必须跳过库存重算。** 语义从「补过单就跳过」扩成「**补单结果不确定就跳过**」——晚算没有代价，算错才有（上游 §5 原话）。
3. 跳过重算时按原因区分 `round{outcome=skipped_supplement}`；异常另走 `step{step=supplement,result=error}`。

**这条 pytest 测不到**（黑盒没法往定时任务里注入异常），它是代码审查发现的。**加 `step{...,result=error}` 指标不是为了让它可被测试，是为了让它在生产里「可见」**——这恰恰是本票存在的理由：对账是全项目最后一个没有观测出口的分支。

---

## 8. 执行阶段待办（本票只定契约，这些留给实现）

| # | 事项 | 归属 |
| --- | --- | --- |
| 1 | 新建 `com/hmdp/observability/ReconcileMetrics.java`，四个方法 + 类注释带 tag 红线 | 改造实施 |
| 2 | `ObservabilityRecorder#increment` javadoc 补 `voucherId / shopId` | 改造实施 |
| 3 | `SeckillReconcileTask` 埋点落地，含补 `reconcileFinishedStocks` 的 `else` 分支、修 §7 的三态问题 | 改造实施 |
| 4 | `docs/observability.md`：§3 指标表加 4 行、tag 红线补 `voucherId/shopId`、扩展点④加 `ReconcileMetrics`、§7 PromQL 速查加对账三条 | 文档同步 |
| 5 | `autotest/common/metrics.py`：delta 把「序列不存在」当 0（§4.3 红线） | 搭框架 |
| 6 | `autotest/testcases/conftest.py`：`reconcile_window_isolated` fixture | 搭框架 |
| 7 | Grafana：对账面板（round 心跳 / supplement 速率 / restock 分布）+ 漂移告警规则 | 可选 |

**PromQL 速查（写进 `docs/observability.md` §7）：**

| 想看什么 | PromQL |
| --- | --- |
| 对账任务是否在跑（5 分钟无心跳即告警） | `sum(increase(hmdp_reconcile_round_total[5m])) or vector(0)` |
| 每分钟补发次数 | `sum(rate(hmdp_reconcile_supplement_total[1m])) by (result)` |
| 是否发生过漂移 | `sum(increase(hmdp_reconcile_restock_total{result="adjusted"}[1h])) or vector(0)` |
| 对账步骤异常（应恒为 0） | `sum(increase(hmdp_reconcile_step_total{result="error"}[5m])) or vector(0)` |

---

## 9. 对下游票的输入

| 下游 | 本票给它的输入 |
| --- | --- |
| **抢券链路测试策略：并发正确性如何证明**（`rDwu6v`） | 对账用例的三条执行约束：① 断言前必须窗口隔离；② 等待一律 `wait_until(timeout=90)`，单轮下限 60s；③ 收敛用例不断言 `restock{result=adjusted}`。加上 §4.2 的三条断言链（可直接落成用例）。 |
| 删除清单（已关闭） | 对账指标放新文件 `ReconcileMetrics`，`SeckillMetrics` 只做删除不做新增，两边 diff 不重叠。 |
| map 的 Not yet specified | 无新增。本票没有产生新的 fog——指标集、tag、断言口径三项都已在本文定死。 |

---

## 10. 不在本文范围内

- 实际写埋点代码、改 `SeckillReconcileTask`、搭 pytest 框架、跑用例 —— map 内只产规格。
- Grafana 面板 JSON 的具体写法 —— 第 8 节给了 PromQL，面板是执行细节。
- 对账是否覆盖**进行中**的券（上游 §7.3）—— 不是本票的问题，不因指标而改变。
- 「丢单笔数」的时序化 —— 需要一个 Gauge 原语，本票已论证当前不做（§6.4）。
