# 舱壁隔离与资源保护设计方案

> 核实基准：源码 `application.yaml` / `ShopController` / `ShopServiceImpl` / `MultiLevelCacheService` / `VoucherOrderServiceImpl`（commit `5feb1e3` 状态）。
> 定位：本文是**工程方案**，不是教学材料。每个参数给「不变量 + 推导」，每个决策给「做/不做的账」。
> 配套：`tech/cache/读链路完整流程.md`（执行版流程）、`docs/observability.md`（指标口径）。

---

## 0. 一句话目标

> **让四个稀缺资源（Tomcat 线程、Lettuce 连接、Hikari 连接、下游自身容量）在任一依赖故障时都不被独占，且配额可由不变量验证、由指标证伪。**

---

## 1. 现状体检（先认清起点）

### 1.1 已建立的

| 资源 | 保护手段 | 现状 |
|---|---|---|
| Tomcat 线程（200） | `seckillBulkhead 100` + `cacheBulkhead 50` | 覆盖 2 条主链路 |
| Hikari 连接（20） | `dbFallbackBulkhead 20` | **仅覆盖降级路径** |
| 熔断器 | `redisBreaker` / `mqBreaker` / `dbBreaker` | 按依赖拆分，粒度正确 |

### 1.2 已识别的四个缺口

| # | 缺口 | 证据 | 风险 |
|---|---|---|---|
| G1 | **配额度量与下游容量不配平** | `cacheBulkhead 50` vs `lettuce max-active 10`（5:1） | 许可 = 虚假承诺，请求排队在连接池而非被快拒 |
| G2 | **主回源路径无 DB 闸门** | `MultiLevelCacheService:298/345`（loadAndCache / rebuildAsync）直调 `dbFallback` | Redis 抖动未到熔断阈值期间，主路径无限制打 DB |
| G3 | **列表类接口裸奔** | `queryShopByType`（GEO 打 Redis+DB）、`queryShopByName`、`BlogController`、`FollowController` 均无 `@Bulkhead` | 用户可见接口可无限抢占 Tomcat 线程 |
| G4 | **Tomcat 总预算无显式配置** | `server.tomcat.*` 未配置，走默认 200 | "200 从哪来"答不出，配额账无法闭合 |

---

## 2. 保护对象与不变量（方案的地基）

### 2.1 四层资源

```
请求进入
  ├─ ① Tomcat 工作线程池（200）      最稀缺 · 保护它收益最大
  ├─ ② Lettuce 连接池（max-active 10）
  ├─ ③ Hikari 连接池（maximum-pool-size 20）
  └─ ④ 下游服务自身容量（Redis 单线程 / MySQL 并发线程）
```

**关键认知**：`cacheBulkhead` / `seckillBulkhead` 保护的是 ①，不是 ②。二者是"划地盘"（数量维度）；②③ 的保护是"配平"（容量维度）。

### 2.2 三条不变量（必须同时成立）

```
I1  许可 ≤ 下游容量
    cacheBulkhead       ≤ lettuce max-active     ← 当前 50 > 10，破
    dbConcurrency       ≤ Hikari max-pool-size   ← 20 = 20，成立

I2  Σ 各链路舱壁配额 ≤ Tomcat 线程池
    100 + 50 + 其他 ≤ 200                        ← 成立，其他剩 50

I3  同资源的 Σ 许可 ≤ 该资源容量
    redisConcurrency（所有路径共享）≤ lettuce 连接数
    dbConcurrency（所有路径共享）   ≤ Hikari 池
```

**I3 是"按资源切"的数学表达**：资源是配额的身份，接口只是使用者。两个接口打同一份资源 → 必须共用同一个配额。

---

## 3. 目标架构

### 3.1 资源与配额的映射

| 资源 | 配额名 | 许可数 | 共享者 |
|---|---|---|---|
| Tomcat 线程 · 领券链路 | `seckillBulkhead` | 100 | 领券入口 |
| Tomcat 线程 · 查询链路 | `cacheBulkhead` | 50 | 商铺详情 + 列表类（G3 修复后） |
| Redis 访问通道 | `redisConcurrency` | **20**（I1 配平，见 §4.1） | 所有走 Redis 的路径 |
| Hikari 连接 | `dbConcurrency` | 20 | 互斥锁回源 + 降级回源 + 重建回源 + 其他查库 |
| RocketMQ 发送 | `mqConcurrency` | 10 | 订单消息发送 |
| 重建/异步任务 | `rebuildExecutor` | core4/max10/q2000/CallerRuns | 已存在，不改 |

**命名变更**：`dbFallbackBulkhead` → `dbConcurrency`。
理由：原名暗示"只管降级"，但按 I3 它必须覆盖**所有打 Hikari 的路径**。名字要表达资源的身份，不是触发场景。

### 3.2 嵌套扣减模型（核心机制）

```
queryById
  │
  ├─ [扣 ①] cacheBulkhead（注解，Controller 层，SEMAPHORE）
  │        └─ 满 → 503 SYS_BUSY（不碰 Redis、不碰 DB）
  │
  ├─ [扣 ②] redisConcurrency（注解在外层方法 or 编程式）
  │        └─ 满 → 503（释放 ①）
  │
  ├─ Redis 命中 → 释放 ② → 释放 ① → 返回
  │
  └─ Redis miss → 需查库
         └─ [扣 ③] dbConcurrency（编程式，在真正查库那一刻）
                ├─ 满 → 抛 BulkheadFullEx → finally 释放 ② ① → 503
                └─ 拿到 → SELECT → 写回 → finally 释放 ③ ② ①
```

**三条铁律**：
1. **先拿上游、再拿下游**——否则占着稀缺资源去等另一个。
2. **栈式释放**（后拿的先还），每个 acquire 配 `finally release`。
3. **许可是"访问许可"不是"方法许可"**——按下游调用的**真实发生点**扣减（`queryWithMutexLock` 的 1s 轮询期间不扣 DB 许可，超时自兜底时才扣）。

> ⚠️ 许可泄漏是静默故障：漏几个之后服务"越跑越慢"，重启即恢复。所有 release 必须包在 `finally` 且自身不抛异常。

---

## 4. 参数推导（每个数字的来源）

### 4.1 Redis 通道：`redisConcurrency` 取值

**冲突**：`cacheBulkhead 50` vs `lettuce max-active 10`。

**解法二选一**（推荐 A）：

| 方案 | 做法 | 理由 |
|---|---|---|
| **A（推荐）** | 连接池提到 50，`redisConcurrency` 保持 50 | Lettuce 是单连接多路复用，`max-active` 是**逻辑连接数**，调大成本极低；且 L1 命中率高时真实并发远低于 50 |
| B | `redisConcurrency` 降到 20，连接池保持 10（2:1） | 改配置更少，但会误伤冷启动/集体过期瞬间 |

**A 的定量依据**：Redis 单机本地网络 RT ≈ 1ms，单连接理论吞吐 ≈ 1000 QPS。50 并发下每连接 5 路复用，单连接 ≈ 200 QPS，远未到瓶颈。**真正的上界是 Tomcat 200 线程，不是连接池。**

### 4.2 `cacheBulkhead` 为何是 50（上界推导）

它约束的是**线程被占满的速度必须慢于熔断器 OPEN 的速度**：

```
单请求最坏占用 = Redis 超时 800ms × 2 次尝试 + 100ms 退避 ≈ 1.7s
50 并发下失败累积速率 ≈ 50 / 1.7s ≈ 29 QPS
redisBreaker 需 20 样本窗口 + 失败率 50% → 约 1s 内 OPEN
```

若放大到 150：1.7s 内积压 150 个卡死线程（占满 3/4 池子），熔断窗口来不及开，其他接口先饿死。

> **50 不是最优点，是安全上界**。它保证「线程占用失控」慢于「熔断接管」。

### 4.3 `dbConcurrency = 20` = Hikari `maximum-pool-size`

**唯一理由**：每个拿到许可的请求都**必须真能拿到连接**，否则许可无意义。这是 I1 的直接应用。
**不许超配**：不能"互斥锁回源配 20 + 降级回源配 20"，那总和 40 > 池 20 → 一半许可拿不到连接。

### 4.4 `mqConcurrency = 10`

RocketMQ producer 默认 `sendMsgThreadNums=4`（实际网络线程）+ 客户端重试。许可取 10 给足排队余量，**再大也没意义**——broker 侧写入才是瓶颈，且 `mqBreaker` 已按依赖拆分。

### 4.5 Tomcat 线程池：显式配置 + 总预算账

```yaml
server:
  tomcat:
    threads:
      max: 200            # 总预算：所有舱壁配额之和的上界
      min-spare: 20
    max-connections: 8192
    accept-count: 200     # 队列，满则拒绝新 TCP
```

**总预算账（I2 的闭合）**：

| 占用方 | 配额 | 剩余 |
|---|---|---|
| seckillBulkhead | 100 | 100 |
| cacheBulkhead | 50 | 50 |
| 其他（登录/博客/GEO 列表等） | ≤50 | 0 |

**加第三个链路前的检查项**：`100 + 50 + X ≤ 200` 不成立时，只能"降别人"或"扩池"，不能直接加。

---

## 5. 落地清单（按性价比排序）

| # | 改动 | 位置 | 成本 | 收益 |
|---|---|---|---|---|
| **M1** | 配平 Redis 通道：`lettuce.max-active: 50`，新增 `redisConcurrency: 50` | `application.yaml` | 2 行 | 修 G1 |
| **M2** | `dbFallbackBulkhead` → `dbConcurrency`，覆盖**所有**查库路径 | yaml + `MultiLevelCacheService` + `ShopServiceImpl` | 中（改 4 处调用） | 修 G2 · 这是最核心的一条 |
| **M3** | `queryShopByType` / `queryShopByName` 挂 `cacheBulkhead`（复用，同资源） | `ShopController` | 2 个注解 | 修 G3 |
| **M4** | 显式配置 `server.tomcat.*` + 总预算注释 | `application.yaml` | 5 行 | 修 G4 |
| **M5** | 新增 `mqConcurrency: 10`，包住 MQ 发送 | `RocketMQProducer` | 小 | 补 MQ 通道 |

**M2 的两种实现**（推荐 ①）：

```
① 统一收口：在 MultiLevelCacheService 内注入 BulkheadRegistry，
   loadAndCache / rebuildAsync / 超时自兜底 三处查库前手动 tryAcquire(dbConcurrency)。
   —— 与 queryByIdFallback 的现有写法一致，风格统一。

② 抽取 DAO 层代理：新建 DbConcurrencyGuard 组件，所有查库走它。
   —— 更彻底（连 UserController.getById 也覆盖），但改动面大。
```

### 5.1 改造后的一致性检查

```
✅ I1  50 ≤ 50（Redis）· 20 ≤ 20（Hikari）· 10 ≤ producer 并发
✅ I2  100 + 50 + 其他 ≤ 200
✅ I3  redisConcurrency 被所有 Redis 路径共享；dbConcurrency 被所有查库路径共享
```

---

## 6. 决策账本（做了什么 · 没做什么）

| 决策 | 做/不做 | 账 |
|---|---|---|
| 按**依赖**切配额，不按接口 | 做 | 多接口共享下游，配额挂接口会各自成立、合起来崩（I3） |
| **信号量**而非线程池隔离 | 做信号量 | Redis 超时 800ms = **有界慢**，非无界阻塞；线程池隔离代价是异步传染（MDC 丢/事务失效），Netflix 后来自弃 Hystrix 默认线程池。**判据：有超时保证用信号量，可能永久阻塞用线程池** |
| DB 配额**共用**（不按路径拆） | 做共用 | 不变量是 `Σ ≤ 池大小`，拆开就超配 |
| 配额**外部化到配置中心** | **不做** | Demo 单机部署，实例数固定；收益（扩缩容自动重算）不存在 |
| **集群级**配额协调 | **不做** | 单实例部署。**但必须能说清**：信号量是进程内计数，N 实例 = N 倍超发；生产解 = 外部 Redis 令牌桶 或 按实例数均摊 |
| **动态配额**（随 RT 自适应） | **不做** | 复杂度陡增、需调参数据支撑、说不清收益。YAGNI |
| **优先级抢占**（报表让路核心） | **不做** | 需完整优先级调度实现；当前无非核心批量任务 |
| 列表类接口**独立**小配额 | 不做独立 | 它们与详情**共用 Redis/DB**，独立配额违反 I3；用共享配额即可 |

---

## 7. 验证方案（方案必须可证伪）

| 验证项 | 手段 | 期望 |
|---|---|---|
| 配额生效 | 压测打满 `cacheBulkhead` | `resilience4j_bulkhead_*` 出现 rejections；熔断器**无反应** |
| 不变量 I1 | 检查 yaml | `resilience4j.bulkhead.instances.*.max-concurrent-calls` ≤ 对应池大小 |
| 不变量 I2 | 求和脚本 | Σ 配额 ≤ 200 |
| I3 共享性 | 代码审查 | 所有 `dbFallback.apply` / `getById` 调用点均经同一配额 |
| 无许可泄漏 | 长稳压测 30min 后查 | `availableConcurrentCalls` 回到满值，不持续下降 |
| 降级链路 | 停 Redis | 全量走 fallback，`dbConcurrency` rejections 增长但 DB 不被打穿 |

**新增指标需求**（现有 `resilience4j_bulkhead_*` 已覆盖 available/permitted/rejected，无需新增埋点）。

---

## 8. 面试话术（对外版本，60 秒）

> "舱壁我按**依赖**切分而不是按接口——因为多个接口共享下游资源，配额挂接口上会各自成立、合起来崩。所以有四个资源各一份配额：Tomcat 线程用 `seckillBulkhead 100` / `cacheBulkhead 50` 划地盘，Redis 访问通道一份，Hikari 连接一份，MQ 发送一份。
>
> 为什么用**并发数**不用 QPS？利特尔法则：并发 = QPS × RT。Redis 抖动时 QPS 没变、RT 从 2ms 涨到 800ms，并发从 1 涨到 400，**QPS 限流器看不出任何异常**。所以慢依赖必须用并发数限流。
>
> 配额不是拍的，是解不变量：**许可 ≤ 下游容量**——`dbConcurrency 20` 就是对齐 Hikari 池，保证每个拿到许可的请求都真能拿到连接。反过来这也让我发现 `cacheBulkhead 50` 配 `lettuce 10` 是破的，已经配平。
>
> 一个接口访问多个资源时**嵌套扣减**：先进 Redis 配额，miss 了再进 DB 配额，栈式释放。**许可是"访问许可"不是"方法许可"**，按下游调用的真实发生点扣。
>
> 我也清楚这版的边界：① 用的是信号量，只挡新请求、不释放已占用的线程，靠 Redis 800ms 超时收敛来兜——这是**有界慢**，不是无界阻塞，所以信号量够用，真出现无界阻塞要换线程池隔离；② 信号量是进程内计数，多实例会 N 倍超发，生产需要外部化配额或按实例数均摊。这两条是我明确知道、也明确没做的。"

---

## 9. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-09-13 | 初稿：现状体检（4 缺口）· 三条不变量 · 嵌套扣减模型 · M1~M5 落地清单 · 决策账本 |
