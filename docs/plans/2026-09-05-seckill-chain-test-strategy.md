# 抢券链路测试策略：并发正确性如何证明

> Wayfinder 票「抢券链路测试策略：并发正确性如何证明」的收口产出（2026-09-05）。
> 三条链路测试策略的最后一张。上游依赖：`2026-09-05-order-state-machine.md`（`used` 布尔语义）、
> `2026-09-05-reconcile-task-new-duty.md`（对账两步：补单→库存重算）、
> `2026-09-05-reconcile-metrics-contract.md`（4 个 Counter，本篇含两处语义修订，见 §9）。

## 0. 结论速览

- **核对口径**：真值 = DB。主判据是**精确等式** `tb_seckill_voucher.stock == initial_stock − COUNT(*)`，
  外加三条交叉验证（DISTINCT / Redis stock / SCARD）；`hmdp.seckill.result` 等指标只做 pytest 功能断言，
  **不进压测后的对账规程**（自己给自己作证）。
- **工具分工**：pytest 管并发正确性（多用户线程池 + 四方对账），JMeter 管吞吐演示
  （阶梯加压 + 集合点两个场景），中间用 **token CSV** 衔接；抢券 **UI 冒烟暂缓**（§9 修订 3）。
- **用例规模**：pytest **19 条（slow 4）** + JMeter 2 场景。停止线沿用三判据
  （确定性 / 零 sleep / 可重复）；性能验收 = **「报告可讲」而非「数字达标」**。

## 1. 已核实的链路事实（断言设计的依据）

对着 `src/main/java` 核实（2026-09-05），写用例前先记住这几条：

| 事实 | 出处 | 测试含义 |
|---|---|---|
| 窗口是 **`[begin, end)` 闭开区间**：`now < begin` 未开始、`now >= end` 已结束 | `SeckillWindow` | 结束瞬间的毫秒点归「已结束」 |
| 窗口 meta 缓存 **TTL 24h**，改 DB 不删 meta 不生效 | `SeckillWarmUpServiceImpl`、`RedisConstants.SECKILL_META_TTL_HOURS` | 窗口 fixture 必须「UPDATE DB + DEL meta + DEL stock」三连 |
| 预热回填只在 **key 不存在 且 活动未开始** 时发生；活动开始后 Redis 库存是唯一真相，缺失则 **fail-closed 拒绝回填** | `warmUpStock` | not_started 请求也会触发预热（库存 key 已存在）；活动中 DEL stock key → 全部 stock_out 且永不回填（W8 的根据） |
| Lua 写四个 key：`seckill:stock/order/claim/txn`，返回 `0/1/2/-1` | `seckill.lua` | 四方对账的对象；`-1` = 事务监听器捕获异常 → 503 SYS_REDIS_UNAVAILABLE |
| 消费端幂等键是 **订单主键**；insert 与 `stock-1` 同事务同生共死 | `createOrderFromMQ` | DB 两账精确等式的来源；`uk_user_voucher` 是最后防线 |
| 应用层限流：提交 **5 次/秒**（按 userId），查询 **10 次/秒**，两者 key 独立 | `SlidingWindowInterceptor` | 烧光提交配额不影响查询配额（R2 的根据） |
| 应用层 429 **无** `X-RateLimit-Layer` 头；网关 429 **有**（`gateway-global-token-bucket`） | `SlidingWindowInterceptor:137`、`openresty/nginx.conf:47` | 两层 429 天然可区分，不用猜归属 |
| 查询被拒只打 `hmdp.ratelimit.fallback{strategy=rejected}`，**不进** `hmdp.seckill.result`；提交被拒打 `result{reason=rate_limited}` | `SlidingWindowInterceptor` | 两类拒绝的指标断言对象不同 |
| 限流器自身故障 → **fail-open** 放行 + `fallback{strategy=fail_open}`；业务层 fail-closed | `SlidingWindowInterceptor` catch 块 | Redis 全下线时一个场景钉死两层哲学（R7） |
| 舱壁/熔断在业务方法之前拦截，`finishSeckill` 从未执行 → **这层拒绝在 `hmdp.seckill.result` 上隐形** | `VoucherOrderServiceImpl#seckillVoucher` 注解 | 舱壁拒绝只能断言 HTTP 503 (SYS_BUSY) + R4J 自身计数 |
| 网关令牌桶：全局 rate=1000/s、capacity=3000；短锁竞争失败保守拒绝 | `token_bucket.lua`、`nginx.conf` | pytest 客户端发不出 1000 rps → 网关触发归 JMeter；短锁竞态不测 |
| 测试档位开关 `seckill:test:protection`（FULL/LEGACY/EARLY）带 **3s 本地快照** | `VoucherOrderServiceImpl#oneOrderProtection` | 切档后必须等 3s 再发请求，否则首个请求仍是旧档（C4 的确定性等待） |

## 2. 核对口径：零超卖零重复拿什么证明

### 2.1 主判据（最终裁定）

活动结束、消费落库完毕、对账跑过之后：

```sql
SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = ?;   -- ① 名额账（不筛 used，语义见上游 §3.1）
SELECT stock   FROM tb_seckill_voucher WHERE voucher_id = ?;  -- ② 库存账
```

断言 **`stock == initial_stock − COUNT(*)`，精确等式，不是 `≤`**。

理由：消费端同一事务里 insert + `stock-1`，两账同生共死，结束后等式必须严格成立；
`≤` 会放过「少卖/丢账」，等式才能同时抓超卖和丢账（包括对账票发现的 `runStep` 吞异常导致的多放库存）。

### 2.2 三条交叉验证（各抓一类等式抓不到的问题）

| 验证 | 断言 | 抓什么 |
|---|---|---|
| 零重复 | `COUNT(DISTINCT user_id) == COUNT(*)` | uk 索引失效/被删（FULL 档下永远该过） |
| Redis↔DB | `GET seckill:stock:{vid} == DB 账` | 入口多扣/少扣、EARLY 档漂移 |
| 集合↔订单 | `SCARD seckill:order:{vid} == COUNT(*)` | 补单漏网（有集合记录却没落库） |

`seckill:claim` hash 与 Prometheus 指标**不进对账规程**：claim 是补单的输入（SCARD 已覆盖其信息量），
指标是被测代码自己 increment 的，循环论证。指标只在 pytest 功能用例里做**结果型增量断言**
（一次一计、取值互斥的可断 `== 1`；路径型只能 `>= 1`，见 map Notes 缓存口径）。

### 2.3 核对时点：两段

- **落库核对**（压完几分钟内）：`wait_until` 轮询 DB 订单数稳定（不再增长）即核对，不等活动结束。
- **收敛核对**（slow）：直连把券 `end_time` 改到 `NOW() − 9min`（跳过真等 8 分钟，登录票「直连注入」先例），
  等对账轮（60s fixedDelay，`wait_until(timeout=90)`）跑完补单+重算后，验精确等式。

EARLY 对照档的核对口径**反过来**：断言「DB 零重复仍成立（uk 兜底）+ Redis stock 相对 DB 账出现
可解释的负偏差（无 sismember 去重 → 同一用户可多次过闸多扣）+ 活动结束后由对账重算收敛」。
EARLY 是「就算 Lua 去重没了，DB 唯一索引还能兜住」的证据用例。

## 3. 工具分工：pytest 管正确性，JMeter 管吞吐，CSV 衔接

| | pytest | JMeter |
|---|---|---|
| 并发正确性 | 多用户线程池抢券 → 四方对账 | ❌ 无法逐单核对 |
| 限流/舱壁/降级分支 | 精确触发 + reason 指标断言 | 只看 429/503 出现率 |
| 时间窗口边界 | 直连改 `begin_time/end_time` | ❌ |
| QPS / P95 / 瞬时峰值 | ❌ | 阶梯加压 + 集合点 |

**登录态（方案 A）**：pytest setup 用 `phone_pool` + `login_as` 批量登录 **1000 个账号**
（自动注册，无预置 SQL），token 写 CSV；JMeter 用 CSV Data Set Config 消费 `authorization` 头。
登录逻辑全项目只有 pytest 一份实现；token TTL 25 天 + 滑动续期，**CSV 生成一次可反复复跑**。

**流量入口统一走 OpenResty 网关**（80 → proxy 8081）——网关令牌桶本身是被测对象，
pytest 功能用例的量级（<100 rps）远够不着桶限。

## 4. 用例清单

### A 块 · 并发正确性（7 条，核心卖点）

| # | 用例 | 手法与断言 | slow |
|---|---|---|---|
| C1 | 多用户并发抢券 + 四方对账 | `user_pool(100)` + Barrier 齐发，压测券 stock=300；`wait_until` 订单数稳定后跑 §2.2 四方核对；`result{reason=success}` 增量 == DB COUNT（窗口隔离，压前抓基线） | |
| C2 | 满员竞速 | stock=50、100 用户并发 → **恰好 50 success、50 stock_out**；对账精确等式 | |
| C3 | 一人一单 | 同用户顺序 10 连发 + 5 线程并发 → 仅 1 单、其余 repeat；`SCARD == 1` | |
| C4 | EARLY 对照档 | `SET seckill:test:protection=EARLY` → **等 3s 快照过期** → 50 用户抢 stock=20 → DB 零重复 + DB 精确等式仍成立 + Redis stock 可解释负偏差 → 恢复 FULL | |
| C5 | 异步落库核对 | 抢券响应 200 拿 orderId → `wait_until` `getSeckillResult` 返回 SUCCESS → DB 有单（最终一致性窗口的行为正向验证） | |
| C6 | 补单自愈（同步补单的直接验证） | 独立券上 `SADD seckill:order` 假丢单用户 + `HSET seckill:claim` 原号 → 改 `end_time=-9min` → `wait_until(90s)` 订单以 **claim 原 orderId** 补齐 + `.supplement{result=ok}` 增量 ==1 + 补后 §2.2 收敛 | ✔ |
| C7 | 对账收敛核对 | 用例券改 `end_time=-9min` → 等对账轮 → `stock == Redis stock == initial − COUNT(*)` 全等 | ✔（与 W6 共用） |

### B 块 · 时间窗口（7 条）

| # | 用例 | 断言 |
|---|---|---|
| W1 | 未开始（begin=+60s）抢券 | not_started；**且 `seckill:stock` 已预热**（预热挂在 ensureWarmed 上，行为事实正向锁定） |
| W2 | 开抢前 1 秒 | not_started |
| W3 | 开抢后 1 秒 | Lua 返回 0（可成功） |
| W4 | 结束前 1 秒（库存足） | 可成功 |
| W5 | 结束后 1 秒（含抢过的老用户） | ended；**窗口校验在 Lua 之前，ended 恒优先于 repeat**（易错优先级，用例钉死） |
| W7 | 券不存在 / 非秒杀券 | voucher_not_seckill；连续两次请求 reason 计数 ==2（第二次走 2min 空值标记，不打 DB） |
| W8 | 活动开始后 Redis 库存丢失 | 活动中 `DEL seckill:stock` → 全部 stock_out，**DB 不回填、key 保持不存在**（「宁少卖不超卖」核心设计的证据用例） |

W6（已结束 + 收敛）并入 C7，不重复计。

### C 块 · 限流与降级（5 条）

| # | 用例 | 手法 | 断言 | slow |
|---|---|---|---|---|
| R1 | 单用户提交限流 | 1s 内**顺序**连发 6 次（本地 RTT ms 级，无需并发） | 第 6 次 429、无 `X-RateLimit-Layer` 头、`result{reason=rate_limited}` 增量 ==1 | |
| R2 | 提交/查询配额独立 | 烧光提交配额后查询仍放行；查询 1s 内第 11 次 | 查询 429 + `fallback{strategy=rejected}` 增量 ==1、`seckill.result` **不动** | |
| R3 | 窗口滑动恢复 | 停 1.1s 重发 | 放行（窗口语义正向验证） | |
| R5 | 舱壁 + MQ fail-closed | **停 RocketMQ broker** → send 阻塞至超时（≈3s）占满在途许可 → 150 线程集合点齐发 | 混合 503 (SYS_BUSY) / 业务码 5004 (mq_send_error)；**零 success、DB 零新增** | ✔ |
| R7 | Redis 全下线 fail-closed 完整链 | 停 Redis 容器 → 连发 → 恢复 | ① `fallback{strategy=fail_open}` 增量 ≥1（限流器放行了）② 业务仍拒绝：503/失败、**DB 零新增** ③ 恢复后 `wait_until` 抢券成功 ④ 恢复后四方对账精确等式不破 | ✔ |

R6（网关令牌桶触发）**归 JMeter 场景 2**：集合点 1500 线程超桶，JMeter Assertion 断言
429 + `X-RateLimit-Layer: gateway-global-token-bucket`。pytest 客户端发不出 1000 rps，不硬造。

### UI / JMeter

- **UI（Playwright）**：暂缓。rTJpc4 已定位「单用户端到端冒烟」（shop-detail.html → 点抢购 → 成功 toast），
  本 map 内不做，恢复时机待定（见 map Not yet specified）。
- **JMeter 场景 1**：阶梯加压 100→500→1000→1500 线程，各档 1~2min，看 QPS 拐点、P95、429/503 比例
  （1500 刻意超过网关桶 rate=1000/s，让限流在报告里可见）。
- **JMeter 场景 2**：Synchronizing Timer 集合点，千线程同时释放模拟「0 点开抢」；挂 R6 断言。

## 5. fixture 清单（conftest 追加）

| fixture | 作用 |
|---|---|
| `make_voucher(stock)` | 动态 INSERT `tb_voucher` + `tb_seckill_voucher`，每用例独立券（可重复性的根）；teardown DELETE 两表行 |
| `set_window(vid, begin_s, end_s)` | UPDATE `begin_time/end_time = NOW()+INTERVAL` + `DEL seckill:meta:{vid} + DEL seckill:stock:{vid}` 三连 |
| `protection_mode(mode)` | SET `seckill:test:protection` + **等 3s** 快照过期 + teardown 恢复 FULL |
| `scrape_metric(name, tags)` | `/actuator/prometheus` 抓取与增量计算（结果型指标断 `==1` 增量） |
| `stop_service(name)` / `restore_service(name)` | `docker compose stop/start` redis / rocketmq（R5/R7 故障注入） |
| `wait_until` | 已有（框架票），对账等待统一 `timeout=90`，禁 sleep |

**时间注入统一用 `NOW() + 偏移`**——与应用 `System.currentTimeMillis()` 同钟域，不引入时钟漂移。

## 6. 操作规程：「并发验证与多方对账」标准流程

**压测前**
1. `docker compose up` 全栈基线，确认健康检查全绿；
2. `make_voucher(300)` 建压测专用券（stock=300 < 1000 用户，让 success/stock_out 都有占比）；
3. 跑 pytest setup 生成 1000-token CSV；
4. 清残留：`SCAN` 匹配 `seckill:*` 与 `rate:sw:*` 全 DEL（上一轮压测的状态会污染本轮对账）；
5. 抓 `/actuator/prometheus` 基线快照（所有 Counter 增量断言的分母）。

**压测中**
- 观察 `hmdp_seckill_result_total` 按 reason 的分布漂移（stock_out 占比随库存消耗上升是预期形态）；
- 观察 `hmdp_seckill_latency_seconds`、R4J 各实例状态、JMeter 聚合报告的 RT 抖动；
- 出现非预期 reason（如 `system_error` 持续增长）立即停，先查再压——压测不是制造故障的遮羞布。

**压测后**
1. pytest 跑对账脚本：`wait_until` DB 订单数稳定（≤5min 窗口）→ §2.2 四方核对；
2. 改压测券 `end_time=-9min` → `wait_until(90s)` 等对账轮 → 收敛核对精确等式；
3. 产物归档：JMeter HTML dashboard + 对账结论（四方数字截图/表格）进报告；
4. teardown：删压测券两表行、清 `seckill:*`、恢复档位 FULL。

## 7. 停止线

**三判据（与缓存/登录链路一致）**：① 确定性（期望值唯一不靠概率）② 零 sleep（`wait_until` 轮询真实状态；
仅对账轮 60s、档位快照 3s 属确定性等待，标 slow）③ 可重复（连跑 3 次一致、用例间用独立券隔离不污染）。

**性能验收 = 「报告可讲」而非「数字达标」**。不设绝对 QPS/P95 门槛（本机 Docker 单机，数字无迁移价值），
四条可验收标准：
1. **拐点可见且归因正确**：哪层先饱和（网关 1000/s → 舱壁 100 → DB）与三层配置对得上；
2. **错误构成可归因**：429/503/5004 比例与理论容量一致，不是随机失败；
3. **压完对账精确等式成立**——压测负载本身就是零超卖证明的输入，性能与正确性一份报告双证据；
4. 实测数字作为**记录**进简历叙事（「单机实测 X」），不是门槛。

**明确不测（7 条，各有先例）**
1. `now == begin/end` 毫秒级边界——黑盒无法确定性命中，降为设计说明（`stale_skip` 先例）；
2. `begin > end` 非法窗口——代码无校验，缺陷现场记录进「发现与风险」，本 map 不改代码（登录票 C 层先例）；
3. pytest 触发网关令牌桶——客户端能力物理不足，JMeter 覆盖；
4. 令牌桶短锁竞争的保守拒绝——微秒竞态，同 ①；
5. fail-open 后「真放行到 Lua 成功」——Redis 全挂 Lua 必败，物理不可分离，R7 断言到「未被 429 拦截」粒度；
6. LEGACY 档（Redisson 锁对照）——保留作回滚档位，测试上无独立叙事价值，不建用例；
7. `getSeckillResult` 的 UNKNOWN 分支——需「排队状态缺失 + DB 查询失败」双故障叠加，构造成本高于叙事价值。

## 8. 密度对齐

| 链路 | 用例 | slow |
|---|---|---|
| 两级缓存 | 15 | 3 |
| 登录 | 23 | 1 |
| **抢券（本篇）** | **19 pytest + 2 JMeter 场景** | **4**（C6/C7/R5/R7） |

同量级，三判据套用，互不对齐——密度由链路自身分支数决定，不凑数。

## 9. 决策修订记录（对上游已关票）

1. **补单机制反转**（修订 `reconcile-task-new-duty.md` 的「不改成直接落库」，怡霖 2026-09-05 拍板）：
   补单从「重发 CREATE 消息」改为**同步调用 `createOrderFromMQ`**。理由：消息补单引入新的
   最终一致性（用不可靠的传输修传输不可靠的问题，自举）；同步化后补单成败本轮可见、
   `.supplement` 语义变干净（= 丢单修复数，天然 exactly-once）、测试断言零等待确定。
   连带：`sendOrderCreate` 失去唯一调用方，连 `sendOrderCreateFallback` 一起删；
   扫描改 **SSCAN 游标 + 每轮 LIMIT（建议 50，50 × 单笔落库上界 < 50s 锁租期）**，
   claim 映射改 **HMGET 一次取齐**（收编原「批量补单」提案；消息批量发送随同步化自然消失）；
   「补单后跳过一轮重算」的理由（异步未落库）消失，是否保留留执行期定。
2. **对账指标契约语义修订**（修订 `reconcile-metrics-contract.md`）：`.supplement{result=ok|error}`
   = **同步补单的落库结果**，删除「异步未落库下轮重复补发并重复计数」的免责注；
   `hmdp.order.consume` 不覆盖补单，补单观测完全归 `ReconcileMetrics`。
3. **抢券 UI 冒烟暂缓**（暂缓 rTJpc4 结论中抢券部分，非永久剔除）：本 map 交付不含抢券 UI 用例，
   恢复时机待定，挂 map Not yet specified。
