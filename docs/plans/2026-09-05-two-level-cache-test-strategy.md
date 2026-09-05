# 两级缓存链路测试策略：一致性怎么观测与断言

> 承接 map《hmdp-pro 转型校园餐饮优惠平台 · 三条链路改造规格与测试策略》
> 票：两级缓存链路测试策略：一致性怎么观测与断言（wayfinder:grilling，2026-09-05 关闭）
> 上游依赖：砍掉支付与关单后，订单状态机与「领取」语义如何定义；对账任务的观测口径
>
> 本文中所有源码结论均已对着 `src/main/java` 与 `redisson-3.13.6.jar` 核实，行号以 2026-09-05 的代码为准。

---

## 0. 结论摘要

| 决策项 | 结论 |
|---|---|
| **断言主依据** | HTTP 响应体 + 直连 Redis/MySQL 状态。Prometheus 指标**退为辅助**，只做「走了哪层」的弱断言 |
| **跨实例脏读** | 单实例等效法（直连改 DB + 删 Redis 模拟他实例 evict），**不起第二实例** |
| **击穿** | 并发互斥 + 伪造 Redisson 锁测有界等待边界，两个用例都做 |
| **降级** | `DEBUG SLEEP` 自动化注入，进常规用例集；停容器另做手工 chaos 演练（不在本票） |
| **停止线** | 三条技术判据：确定性 / 零 sleep / 可重复。不通过的一律降级为设计说明，不写用例 |
| **用例规模** | 15 条自动化（其中 3 条 slow），3 条明确不测 |

---

## 1. 断言依据为什么不是指标

### 1.1 循环论证

`hmdp.cache.hit` 是缓存代码自己 `increment` 出来的。用它断言缓存行为，等于让嫌疑犯写证词。
Redis 里的 key 值 / TTL / `expireTime` 字段是**副作用事实**，HTTP 响应是**外部可观测行为** —— 这两样独立于被测代码是否成立，才配当主依据。

### 1.2 结果型指标 vs 路径型指标

| | 结果型（如 `hmdp.seckill.result{reason}`） | 路径型（如 `hmdp.cache.hit{level}`） |
|---|---|---|
| 一次请求打几次 | 一次 | **可叠加多次** |
| 取值是否互斥 | 是 | 否 |
| 能否精确断言 | 能（`== 1`） | **不能**，只能弱断言（`>= 1`） |

上游《对账任务的观测口径》那张票的契约之所以成立，是因为它对付的是前者。**路径型指标照搬同一套精确断言方法是错的。**

### 1.3 逐个指标的可用性判定

| 指标 | 打点位置 | 判定 |
|---|---|---|
| `hmdp.cache.hit{level=l1}` | 仅 L1 命中分支（MultiLevelCacheService:136），一次一计 | ✅ **干净**，可精确断言 `== 1`（全项目唯一） |
| `hmdp.cache.hit{level=l2}` | 未过期 / 逻辑过期 / 空值标记 / 锁内双重检查 / 轮询命中，**五处** | ❌ 脏，只能弱断言 |
| `hmdp.cache.hit{level=db}` | 进互斥锁**之前**（:172），锁内命中会再计一次 l2 | ❌ 脏。**`db 增量 == 1` 不代表只回源一次** |
| `hmdp.cache.rebuild{result}` | 异步重建结束（:332/:335），一次一计 | ✅ 干净，但**需窗口隔离** |
| `hmdp.cache.stale_skip` | 版本核验失败（:373），一次一计 | ✅ 干净，但**触发窗口测不到**（见 §4.1） |
| `hmdp.resilience.fallback{breaker,kind}` | fallback 方法内手动打点 | ✅ 干净 |
| `resilience4j_circuitbreaker_state{name}` | R4J 自注册 Gauge | ✅ 全项目唯一的 Gauge |

---

## 2. 被测行为事实基线（源码核实）

| 项 | 值 | 出处 |
|---|---|---|
| L1 Caffeine | `maximumSize=10_000`，`expireAfterWrite=30s` | CaffeineConfig:30-31 |
| L2 逻辑 TTL | 30min + 0~20% jitter → 实际 **30~36min** | RedisConstants:11 + writeWithLogicalExpire:387-389 |
| L2 物理 TTL | `baseSec*3` = **5400s（90min）**，不含 jitter | writeWithLogicalExpire:393-394 |
| 空值标记 TTL | `2 + rand(0,2)` 分钟 → **120~240s** | loadAndCache:283-284 |
| 击穿锁 | Redisson `lock:shop:{id}`，`tryLock(0, 30s)` | queryWithMutexLock:212 |
| 有界等待 | 轮询上限 `MUTEX_WAIT_MILLIS=1000`，步长 50ms；超限**自行回源 DB 且不写回缓存** | queryWithMutexLock:237-261 |
| 写后失效 | `afterCommit` 内 `evict()`（L1 invalidate + Redis delete），失败重试 2 次后仅 `log.error` | ShopServiceImpl:163-202 |
| 版本核验 | `Shop::getUpdateTime` vs `baseMapper.selectUpdateTimeById`，stale → 放弃写回 | isStaleSnapshot:356-378 |
| 读接口 | `GET /shop/{id}`，**无鉴权** | ShopController:33-36 |
| 熔断（redisBreaker） | 50% / COUNT_BASED 20 / minimum 10 / open→half_open 10s / half_open 3 次 | application.yaml:94-111 |
| 重试（cacheQueryRetry） | max-attempts 2，100ms 指数退避；**Retry 在 CircuitBreaker 外层** | application.yaml:161-165 |
| 舱壁 | cacheBulkhead 50 / dbFallbackBulkhead 20（对齐 Hikari 池） | application.yaml:147-156 |

**两条由配置推出的测试参数**：

1. **1 次 HTTP 请求会被熔断记成 2 次调用**（Retry 在外层，2 次尝试）。所以故障注入后发 **6 次请求** 即可凑够 12 次计数（越过 `minimum-number-of-calls: 10`），失败率 100% → 熔断打开。
2. **熔断打开前的那 10 次调用每次都会走 fallback 回源 DB**（真实异常同样触发 fallback）。所以「Redis 挂了但熔断还没开」才是**主要的降级验证窗口**，不能只盯着 open 状态断言。

---

## 3. 测试用例清单

标记说明：`slow` = 含 TTL / 熔断恢复的确定性等待（>10s）；`chaos` = 会阻塞全局 Redis，必须串行且禁止其他流量；`isolate` = 需窗口隔离或前后清理。

### A 组 · 命中路径与层级

#### A1 · L1 命中
- **前置**：`DEL cache:shop:1`
- **步骤**：GET `/shop/1`（填 L1+L2） → 记指标基线 → 再 GET
- **断言**：`hit{level=l1}` 增量 **== 1**；响应体与 DB 一致
- **判定**：确定性 ✅（l1 一次一计，全项目唯一可精确断言的指标）

#### A2 · L1 过期回落 L2
- **前置**：承接 A1（L1 已有值）
- **步骤**：等待 L1 过期 → GET
- **断言**：`hit{level=l1}` 增量 **== 0**；`hit{level=l2}` 增量 **>= 1**；数据正确
- **标记**：`slow`（31s）

#### A3 · 冷启动回源
- **前置**：`DEL cache:shop:1`
- **步骤**：GET
- **断言**：数据正确；Redis key 重建且 `data` 与 DB 一致；`TTL key` ∈ `(0, 5400]`；`hit{level=db}` 增量 >= 1

#### A4 · 逻辑过期返回旧值 + 异步重建 + L1 残留（慢）
- **前置**：DB 把 shop 1 改名为「新名」；直连 Redis 塞 `{"data":{...,"name":"旧名"},"expireTime":<过去时刻>}`
- **步骤**：GET → 记耗时 → `wait_until` Redis 内 `data.name == "新名"`（timeout 10s）→ 立即再 GET → 等 L1 过期后再 GET
- **断言**：
  1. 第一次响应 `name == "旧名"`（逻辑过期同步返回旧值，不阻塞）
  2. 异步重建后 Redis 内 `data.name == "新名"`，且 `rebuild{result=ok}` 增量 >= 1
  3. **重建完成后立即再 GET，仍返回「旧名」**
  4. L1 过期后 GET 返回「新名」
- **标记**：`slow`（31s）

> **第 3 条是这个用例的价值所在，也是一个容易踩的坑。**
> 源码 `MultiLevelCacheService:160` 在逻辑过期分支把**旧值也 put 进了 L1**，而异步重建（`rebuildAsync`）**只写 Redis、不清 L1**。
> 后果：**异步重建完成后，本实例仍会在 L1 剩余 TTL 内继续返回旧值。**
> 所以本链路真实的脏读窗口上界不是「异步重建耗时」，而是 **`L1 TTL（30s）`** —— 这是设计事实，不是 bug，用例要把它量化出来。

### B 组 · 一致性窗口

#### B1 · 写后立即生效
- **步骤**：GET（填充）→ `PUT /shop {"id":1,"name":"新名"}` → GET
- **断言**：响应 `name == "新名"`；Redis key 已被删（evict 生效）
- **说明**：`evict()` 同时清 L1 与 L2，且在 `afterCommit` 内同步执行，所以**正常情况下一致性窗口为零**。这条用例是基线，不是终点。

#### B2 · L1 跨实例脏读（单实例等效法）— `slow`
- **步骤**：
  1. GET（填 L1+L2，值为「旧名」）
  2. 直连 MySQL `UPDATE tb_shop SET name='新名' WHERE id=1`
  3. 直连 Redis `DEL cache:shop:1`
  4. GET → **断言返回「旧名」**（脏读命中）
  5. 等 L1 过期后 GET → **断言返回「新名」**
- **断言**：脏读被复现；最坏脏读时长 <= 30s（L1 TTL 收敛）
- **等效性论证**：跨实例脏读的机制本质是「本实例的 L1 不知道别的实例发生过 `evict`」。步骤 2+3 精确构造的正是**别的实例跑完 `update` + `evict` 之后系统应处的最终状态**。被测行为 100% 一致，成本为零。
- **唯一未覆盖**：主动侧「实例 B 的 `update` 不清实例 A 的 L1」。由代码走查补 —— `evict()` 里只有 `shopLocalCache.invalidate()`（本实例），**没有任何广播机制**（无 Redis Pub/Sub、无 Canal）。这是**有意的架构权衡**，由 30s TTL 收敛。

#### B3 · 物理 TTL 保险丝（配置核查）
- **步骤**：GET 生成 key → `TTL cache:shop:1`
- **断言**：`TTL` ∈ `(0, 5400]`
- **说明**：这是**配置核查式断言**，不是运行时自愈测试。物理 TTL 是最坏情况下（主动删除 + 异步重建同时失败）的兜底收敛时间，验证「最迟 90min 自愈」不需要真等 90 分钟 —— 断言 TTL 上界即可。

### C 组 · 三个经典问题

#### C1 · 穿透：空值标记
- **前置**：`DEL cache:shop:999999`
- **步骤**：GET `/shop/999999` → 记基线 → 再 GET 一次
- **断言**：
  1. 两次响应均 `success=false`（"店铺不存在！"）
  2. Redis `cache:shop:999999 == ""`
  3. `TTL` ∈ `(0, 240]` 秒（对应 2~4min + jitter）
  4. **第二次请求 `hit{level=db}` 增量 == 0 且 `hit{level=l2}` 增量 >= 1**
- **说明**：第 4 条是确定的 —— 空值分支（`json != null`）在 `hit{level=db}` 打点**之前**就 return 了。

#### C2 · 穿透边界：能力边界的诚实声明 — `isolate`
- **步骤**：依次 GET 100 个**互不相同**的不存在 id（建议 `900001~900100` 全新号段）
- **断言**：100 个空值 key 全部创建；`hit{level=db}` 增量 **== 100**；全部响应"店铺不存在"
- **清理**：用例结束必须删除这 100 个 key，否则污染后续用例
- **这条用例的价值是纠正一个常见误解**：空值标记防的是「**同一个**不存在的 id 被反复查」，**防不住**「不同的不存在 id 各查一次」—— 后者每次都会穿透到 DB。真正的防护要靠布隆过滤器或入参白名单，**本项目未做**。这是能力边界，要在用例文档里如实写明，不要让它看起来「穿透问题已解决」。

#### C3 · 击穿：并发互斥
- **前置**：`DEL cache:shop:1`
- **步骤**：50 并发 GET `/shop/1`
- **断言**：全部 200 且数据正确；**`hit{level=l2}` 增量 > 0**
- **断言逻辑**：若无互斥锁，L2 全程为空，每个请求各自回源，l2 增量必然为 0。l2 有增量 = 有请求在 1s 有界轮询中拿到了别人的重建结果 = 互斥锁在工作。
- **阈值**：实施时按实测标定（初取 `>= 并发数 × 0.5`）
- **判据符合性**：⚠️ **弱确定性** —— 该断言依赖 DB 回源快于轮询上限。若实测不稳定，**降级为「只断言正确性与延迟」**，并在用例文档注明降级原因。不要为了保住断言而调高并发数硬凑。

#### C4 · 击穿：有界等待边界（伪造 Redisson 锁）
- **前置**：`DEL cache:shop:1`；`HSET lock:shop:1 pytest-fake 1` + `PEXPIRE lock:shop:1 30000`
- **步骤**：GET `/shop/1`（计时）
- **断言**：
  1. 响应 200 且数据正确
  2. **耗时 ∈ `[1s, 3s]`** —— 证明确实等满了 `MUTEX_WAIT_MILLIS=1000` 就自行回源，**而不是死等 30s 锁租期**
  3. **请求后 Redis 里不存在 `cache:shop:1`** —— 超限回源路径（:261）**不写回缓存**
- **清理**：`DEL lock:shop:1`
- **判据符合性**：✅ 完全确定，无随机性

> **故障注入可行性已核实**（提取自 `redisson-3.13.6.jar` 的 `RedissonLock.class`）：
> 加锁 Lua 以 `exists KEYS[1]` + `hexists KEYS[1] ARGV[2]` 判定，失败时 `return pttl KEYS[1]`。
> 锁 key 是 **hash**，只要 key 已存在且 field 不等于应用侧的 `uuid:threadId` 即加锁失败。
> pytest 侧用 `HSET lock:shop:1 pytest-fake 1` 即可占住，两行 redis-py，无需改任何生产代码。

#### C5 · 雪崩：TTL jitter 机制存在性
- **前置**：`DEL cache:shop:1..10`，依次 GET 触发写入
- **步骤**：直连读 10 个 key 的 `expireTime` 字段
- **断言**：
  1. **主断言（100% 确定）**：全部 `expireTime` ∈ `[now+30min, now+36min]`
  2. 次级断言（概率性）：至少 2 个互不相同
- **说明**：本用例证明的是**机制存在**，**不证明「雪崩被防止」** —— 后者需要规模与压测，超出本票范围。写文档时必须区分这两者，不要把「我验证了 jitter 存在」说成「我验证了雪崩防护有效」。

#### C6 · 重建去重 — `isolate`
- **前置**：**窗口隔离**（确保其他 13 个商铺 key 均处于未过期状态）；向 `cache:shop:1` 塞过期 JSON
- **步骤**：并发 10 次 GET `/shop/1`
- **断言**：`hmdp.cache.rebuild{result=ok}` 增量 **== 1**
- **判据符合性**：✅ 10 个请求同时触发重建，`lock:shop:{id}` 保证只有 1 个能拿到锁真正执行
- **风险与约束**：`rebuild` 是**全局计数**，任何其他 key 在此期间发生重建都会污染计数。必须窗口隔离：断言窗口内不做任何其他请求，且断言前后差值窗口尽量短。

### D 组 · 降级与容错

#### D1 · Redis 卡死降级 — `chaos` `serial`
- **前置**：记指标基线
- **步骤**：`DEBUG SLEEP 8` → 立即连续发 10 次 GET `/shop/1`
- **断言**：
  1. **全部 200，且数据是 DB 真值**（降级不降级正确性）
  2. `resilience4j_circuitbreaker_state{name="redisBreaker"}` 由 0 变为 1（open）
  3. `hmdp.resilience.fallback{breaker="redisBreaker",kind="error"}` 增量 > 0（学习期真实失败）
  4. `hmdp.resilience.fallback{breaker="redisBreaker",kind="not_permitted"}` 增量 > 0（熔断打开后快速失败）
  5. `hmdp.resilience.retry{retry="cacheQueryRetry",kind="retry"}` 增量 > 0（重试层生效）
- **前置条件**：`DEBUG` 命令在 Redis 7-alpine 未被禁用（**实施时先实测**）
- **隔离要求**：`DEBUG SLEEP` 会阻塞整个 Redis 事件循环，期间**所有**链路都会超时。此用例必须串行，且禁止并发执行其他用例。

#### D2 · 熔断恢复 — `slow`
- **前置**：承接 D1
- **步骤**：`wait_until` 状态 == 2（half_open），timeout 20s → 发 3 次请求 → `wait_until` 状态 == 0（closed）
- **断言**：状态最终回到 0；恢复后 `hit{level=l2}` 增量恢复（重新走缓存路径）
- **耗时**：约 10s（open 持续）+ 试探时间

---

## 4. 明确不测的三条（含完整理由）

### 4.1 `stale_skip` 版本核验竞态 —— 不测

推导一遍就能否决：

- **逻辑过期重建路径下永远不 stale**。`rebuildAsync` 里 `data = dbFallback.apply(id)` 是**刚从 DB 查出来的**，`snapVer == dbVer`，`snapVer.isBefore(dbVer)` 恒为 false。
- **冷启动回源路径同理**，`loadAndCache` 里 `result = dbFallback.apply(id)` 也是刚查出来的。
- 所以 stale 只在**竞态**下发生：查 DB 得到 T2 → 期间另一事务更新到 T3 并删除缓存 → 比对 DB 当前版本 T3 → `T2 < T3` → stale。
- 而「查 DB」与「比对 DB」在 `loadAndCache` 里是**紧挨着的两行**（:280 → :289），间隔**几十微秒**。pytest 无法精确插入这个窗口。
- 唯一办法是高频并发压测碰运气。粗估：每秒发 100 次 UPDATE，命中 ~0.1ms 窗口的概率约 1%/秒 —— 跑几分钟才可能撞上一次，**必然 flaky**。

**处理**：作为设计说明讲清楚 —— 代码里有这个防御，触发窗口是微秒级，测试不做覆盖，**靠 code review 保证**。诚实承认测不了，远好于写一个十次里红三次的用例。

### 4.2 `evict` 失败后的 90min 兜底 —— 不测

要注入 `evict` 失败（让 `multiLevelCache.evict` 抛异常）才能触发，需改生产代码或注入 Redis 故障。成本远超收益，降级为 B3 的 TTL 上界核查 + 代码走查。

### 4.3 `dbFallbackBulkhead` 打满（20 许可）—— 不测

原方案是并发 25 请求，断言 20 成功 + 5 被拒。但**确定性不足**：DB 主键查询在毫秒级，许可被快速释放，25 并发下很可能 25 个全成功；要稳定打满就得拉到数百并发并依赖时序，又引入新的抖动。**按判据①淘汰。**

「许可数 20 对齐 Hikari 池 20」是**配置事实**，走查即可，不需要跑出来。

---

## 5. 停止线：三条技术判据

1. **确定性** —— 期望值必须唯一确定，不能是「大概率如此」。凡是需要靠概率撞的（§4.1）直接淘汰；依赖时序抖动的（C3）必须给出降级方案。
2. **零 sleep** —— 等待一律 `wait_until(condition, timeout)` 轮询**真实状态变化**。唯一可接受的固定等待是 **TTL / 熔断的确定性等待**（L1 30s、open→half_open 10s），这类用例标 `slow`，但等待的**是配置的常量，不是碰运气的异步完成**。
3. **可重复** —— 同一用例连跑 3 次结果一致，且用例之间不互相污染：每个用例开头记指标基线、结束时清理自己创建的 key（C2 的 100 个空值 key、C4 的伪造锁）。

**不通过这三条的，一律移到「设计说明」区，不写用例。**

---

## 6. 可观测性前置条件清单

跑上述用例前必须就绪的事项。分四类：

### 6.1 端点与配置侧

| # | 条件 | 现状 |
|---|---|---|
| 1 | `/actuator/prometheus` 已暴露 | ✅ `application.yaml:171` 已 `include: prometheus,health,info` |
| 2 | `resilience4j-micrometer` 在依赖里 | ✅ 否则 `resilience4j_circuitbreaker_state` Gauge 不出现 |
| 3 | 全局 tag `application` | ⚠️ 已配置（:172-174）。pytest 解析指标时**必须按 `application` 过滤**，否则同机其他实例会污染 |
| 4 | **指标序列缺失 = 计数 0** | ⚠️ **必须处理**。Micrometer 的 Counter 在首次 `increment` 前**不会导出该序列**，prometheus 端点里查不到 `hmdp_cache_hit_total{level="l1"}` 是正常的。指标读取工具必须把缺失序列按 0 处理，否则「首次运行」会全量报错 |
| 5 | 测试期间禁止其他流量 | ⚠️ 指标是累计 Counter，任何手工浏览器访问都会污染增量。**缓存链路用例必须串行执行** |

### 6.2 代码侧（改造项，不属于本 map 的交付范围）

| # | 改造 | 收益 |
|---|---|---|
| 6 | `hit{level=db}` 从 `queryWithMutexLock` **之前**（:172）挪到 `loadAndCache` 内真正回源处；锁内双重检查命中（:223）与轮询命中（:242）不再重复计 l2 | C3 的断言从「`l2 > 0`」升级为「`db == 1` 且 `l2 == N-1`」，即「击穿只回源一次」可被证明 |
| 7 | 确认超限自行回源（:261）**不写回缓存**是有意设计还是遗漏 | 若为有意，在代码注释写明理由；若为遗漏，补写回。目前 C4 按「有意」断言 |

> 第 6 项不阻塞本票 —— C3 用 `l2 > 0` 也能证明互斥锁生效，改造只是让断言更精确。

### 6.3 环境侧

| # | 条件 | 备注 |
|---|---|---|
| 8 | Redis 可直连（宿主机映射端口） | 用于读 key / TTL / expireTime、伪造锁、`DEBUG SLEEP` |
| 9 | MySQL 可直连（宿主机 3307） | 用于改 `name` / 校验 DB 真值 |
| 10 | **`DEBUG` 命令未被禁用** | ⚠️ **实施时先实测**，Redis 7-alpine 默认可用但需确认 |
| 11 | 应用单实例运行 | B2 的等效法依赖「只有一个 JVM 的 L1」 |

### 6.4 测试工程侧

| # | 条件 |
|---|---|
| 12 | 指标基线工具：每个用例开头快照 `hmdp_cache_hit` 各 level、`rebuild`、`fallback`、`breaker.transition` 的当前值，结束时取差值 |
| 13 | 窗口隔离工具：C6 / D1 需要「断言窗口内无其他请求」的保证，串行执行 + 显式注释 |
| 14 | 清理 fixture：C2 的 100 个空值 key、C4 的伪造锁、A/B 组的 `cache:shop:{id}`，用例结束必须清理 |
| 15 | 熔断状态复位：D1/D2 后必须 `wait_until` 状态回到 0，否则后续用例会走降级路径而断言失败 |

---

## 7. 可复用的关键测试手法

这四条是本票最有价值的产出，其余两条链路（登录 / 抢券）可直接复用：

1. **用状态注入代替时间等待** —— `RedisData` 就是一个 JSON：`{"data":{...},"expireTime":"..."}`。直连 Redis 塞一个 `expireTime` 在过去的 JSON，立刻得到「逻辑已过期」，不用等 30 分钟。**把时间依赖换成状态依赖**，是本链路测试从「等」变「摆」的关键。
2. **用 redis-py 伪造 Redisson 锁做故障注入** —— 锁是 hash，`HSET <key> pytest-fake 1` + `PEXPIRE` 即可占住（脚本已核实，见 C4）。比停容器 / 锁表安全得多，且精确到毫秒级。
3. **单实例等效复现多实例问题** —— 直连改 DB + 删缓存，等价于「别的实例跑完 update + evict 后的最终状态」。零成本覆盖分布式一致性语义。
4. **`DEBUG SLEEP` 做依赖故障注入** —— 比停容器恢复快、无数据丢失、可重复，且能精确控制故障时长。

---

## 8. 遗留与下游影响

| 项 | 去向 |
|---|---|
| 改造项 6（打点位置） | 不新开 wayfinder 票（属于执行项而非决策项），已记入 §6.2 前置条件清单，随后续代码改造一并处理 |
| 改造项 7（超限回源不写缓存） | 同上，需确认后补注释或补写回 |
| L1 无跨实例广播（Pub/Sub / Canal 均无） | **有意的架构权衡**，由 30s TTL 收敛。B2 用例把窗口量化为「<= 30s」，面试时讲「我测出了这个窗口并选择了 TTL 收敛」而非回避 |
| 空值标记防不住批量不同 id 穿透 | C2 已作为能力边界写明，不要包装成「穿透已解决」 |
| JMeter / Postman 在本链路的分工 | 未定。属于 map 的 **Not yet specified**，等三条链路策略齐了再定，本票不涉及 |
| 种子数据是否足够 | 未定。同上，属 map 的雾区 |
