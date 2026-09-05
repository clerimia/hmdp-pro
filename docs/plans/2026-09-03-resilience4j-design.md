# Resilience4J 容错改造设计

日期：2026-09-03
状态：设计已确认，分 P0–P3 落地

## 1. 目标

为 hmdp-pro 秒杀链路引入 Resilience4J，建立**可观测、可自动恢复**的容错体系：

- 收敛 Redis / MySQL / RocketMQ 三个依赖宕机时的爆炸半径
- 建立异常分层，让"业务失败"与"系统故障"在系统里是两种东西
- 补齐超时、熔断、隔离、降级、兜底五道防线
- 所有降级动作可观测（打点 + traceId）

## 2. 现状摸底结论（改造前的事实基线）

| 项 | 现状 | 证据 |
|---|---|---|
| Spring Boot | 2.3.12.RELEASE + Java 8 | `pom.xml:8` |
| AOP starter | **缺失**，本地仓库也未缓存 | `pom.xml:19-107` |
| RocketMQ 客户端 | 原生 `rocketmq-client 4.9.7`，非 starter，手工装配 | `pom.xml:93-97` |
| 全局异常处理 | 仅一个 `@ExceptionHandler(RuntimeException.class)`，统一返回"服务器异常" | `config/WebExceptionAdvice.java:12-16` |
| 异常体系 | 无 `BusinessException`、无错误码，失败只带 `errorMsg` 字符串 | `dto/Result.java` |
| Redis 命令超时 | **未配置**，Lettuce 默认 60s | `application.yaml:11-21` |
| Redisson | 只配 address/password，`tryLock()` 全部无参（默认 30s + watchdog） | `config/RedissonConfig.java:22-32` |
| 数据源 | Hikari 默认参数，`connection-timeout`/`maximum-pool-size` 未配置 | `application.yaml:6-10` |
| MQ 发送 | 已设 `retryTimesWhenSendFailed=2`、`sendMsgTimeout=3000` | `mq/RocketMQProducer.java:46-47` |
| MQ 消费 | `RECONSUME_LATER`，无自定义重试上限/死信 | `mq/OrderMQConsumer.java:63` |
| 已有兜底 | 事务消息 + 回查 + 对账补单；限流 fail-open；缓存防穿透/击穿/雪崩 | 多处 |

**关键发现**：Mode A 的本地事务（跑 Lua 扣库存）由 `sendMessageInTransaction` **在请求线程上同步执行**
（只有 Broker 回查才走回查线程池）。这意味着秒杀的 Redis 交互全在 Tomcat 线程上，
请求线程本身就是最稀缺的资源。

**订单号生成不依赖 Redis**：`CachedUidGenerator` 启动时预填 8192 << boostPower = 65536 个 UID 进
RingBuffer，取号无锁 CAS；DB 短暂不可用时 RingBuffer 余量即天然降级窗口（`config/UidGeneratorConfig.java:17-20`）。

## 3. 版本与依赖约束

- **`resilience4j-spring-boot2:1.7.1`**。不能上 2.x——Boot 2.3.12 + Java 8 不满足 2.x 的 Java 17 要求。
- **必须补 `spring-boot-starter-aop`**。Resilience4J 的 `@CircuitBreaker/@Retry/@Bulkhead/@TimeLimiter`
  全部依赖 Aspect 实现，**缺 AOP 会静默失效**（注解不报错、也不生效，是最隐蔽的坑）。
- 配置全部写在 `application.yaml` 的 `resilience4j.*` 下，不用 Java DSL——便于直接展示和讲解。

## 4. 异常体系（P0，所有后续工作的前提）

### 为什么要先做

熔断器的 `ignoreExceptions` 决定"哪些异常不计入失败率"。当前"库存不足"和"Redis 超时"在系统里
都是 `RuntimeException`，无法区分 → 秒杀高峰期"库存不足"是最高频结果，会被算成故障率，**导致误熔断**。

### 分层设计

| 层次 | 类型 | 计入熔断 | 全局处理器返回 |
|---|---|---|---|
| 业务失败 | `BusinessException(ErrorCode)` | **否**（配 `ignoreExceptions`） | HTTP 200 + `code` |
| 系统故障 | `SystemException`（含 Redis/MQ/DB 不可用） | 是 | HTTP 503 + traceId |
| 参数错误 | `MethodArgumentNotValidException` | 否 | HTTP 400 |
| 熔断/隔离 | `CallNotPermittedException`、`BulkheadFullException` | 否 | HTTP 503「活动火爆，请稍后」 |
| 兜底 | 其他 `RuntimeException` | 是（未知错误） | HTTP 500 + traceId |

`Result` 增加 `code` 字段，保留 `errorMsg` 向后兼容（前端无需同步改造）。

### 关键错误码

- `ORDER_STOCK_OUT`（库存不足）、`ORDER_REPEAT`（重复领取）：业务码，不熔断
- `ORDER_PROCESSING`（领取处理中）：**降级专用**，见第 7 节语义决策
- `SYS_REDIS_UNAVAILABLE`、`SYS_MQ_UNAVAILABLE`、`SYS_DB_UNAVAILABLE`、`SYS_BUSY`（熔断/隔离）

## 5. 三个熔断实例（P1/P2）

| 实例 | 包裹点 | 失败率 | 滑动窗口 | 半开等待 | 忽略 | 降级动作 |
|---|---|---|---|---|---|---|
| `redisBreaker` | 秒杀 Lua、缓存读写、Redisson 加锁 | 50% | 20 次调用 | 10s | BusinessException | 秒杀→快速失败；查询→回源 DB |
| `dbBreaker` | 消费端落库、事务回查 | 50% | 20 | 15s | BusinessException | `RECONSUME_LATER`，交给对账 |
| `mqBreaker` | 事务消息发送 | 50% | 20 | 10s | BusinessException | 直接「活动火爆」，不打 DB |

**按依赖拆分而非全局共用**：Redis 抖动不应熔断 MQ 通道，否则爆炸半径反而变大。

### 超时是本次改造里比熔断更救命的一项

| 位置 | 现状 | 目标 |
|---|---|---|
| Redis 命令超时 | Lettuce 默认 60s | **800ms** |
| Redisson `tryLock` | 无参（30s + watchdog） | 显式 wait/lease |
| Hikari `connection-timeout` | 默认 30s | 3s |
| Hikari `maximum-pool-size` | 默认 10 | 20 |

Redis 挂掉时，60s 超时会让 Tomcat 线程池在几秒内被慢请求占满——这才是当前真正的雪崩路径。

### R4J 1.7 的 TimeLimiter 限制

`@TimeLimiter` 要求方法返回 `CompletionStage`，**同步方法上不生效**。
所以秒杀的超时控制只能靠**客户端自身超时**，TimeLimiter 只用在异步路径（缓存异步重建）。

### 切面顺序

R4J 默认顺序（数字小的在外层）：Retry → CircuitBreaker → RateLimiter → TimeLimiter → Bulkhead（最内层）。
推论：**一次请求内的 N 次重试会被熔断器计为 N 次调用**，调 `failureRateThreshold` 时要把这点算进去。

## 6. 舱壁选型：秒杀用信号量，不是线程池

### 概念澄清

舱壁隔离隔离的是**资源额度**（故障的爆炸半径），不是"隔离线程池"。
线程池隔离 = 每个依赖独占 N 个线程；信号量隔离 = 每个依赖独占 N 个并发许可。

### 为什么秒杀选信号量

1. **同步接口上做线程池隔离是纯亏**。Mode A 返回 `Result`，本地事务在请求线程执行 →
   套 ThreadPoolBulkhead 后请求线程照样阻塞等待池线程，**Tomcat 线程一个都没解放**，
   却多付一次线程切换 + 队列调度。真要解放 Tomcat 线程，必须全链路返回 `CompletionStage` 异步化，
   那会破坏秒杀"同步返回抢到/没抢到"的语义。
2. **线程绑定的上下文会断**：MDC traceId、`UserHolder`（`ThreadLocal`）跨线程全断，
   目前只有 `traceAwareExecutor` 装了 `MdcTaskDecorator`。
3. **延迟可预测**：信号量 `maxWaitDuration=0` → 许可耗尽立即拒，用户看到「活动火爆」；
   线程池有队列，故障期延迟变成"排队等待"，用户体验更差。
4. **不必靠线程打断**：只有"客户端超时不可控"才需要 `Future.cancel`。本项目
   Lettuce / Hikari / RocketMQ client 都有 timeout 参数，超时可控。

### 信号量的硬前提

信号量**只能拒绝新请求，救不了已卡住的线程**——许可会被慢调用占光，最终全拒。
所以 **Redis 命令超时 60s → 800ms 是信号量的前置条件**，必须先做。

### 落点

| 位置 | 选型 | 理由 |
|---|---|---|
| 秒杀入口 / 缓存查询 | 信号量（`maxWaitDuration: 0`） | 同步、低延迟、上下文绑定 |
| 缓存异步重建 | **独立线程池** | 本来就要异步；现有 `traceAwareExecutor` 是 `CallerRunsPolicy` + 队列 2000，打满会回压请求线程 |
| MQ 消费端 | **独立线程池** | DB 慢不能拖死拉取线程 |

## 7. 降级矩阵与两个语义决策

### 三个概念的关系

- **限流**管准入（进来多少）· 入口侧
- **熔断**管调用（还调不调）· 出口侧
- **降级**管返回（拿不到结果时给什么）· 结果侧

熔断是触发条件，降级是触发后的动作。

### 降级矩阵

| 故障 | 秒杀领券 | 商铺/券查询 | 非核心写入 |
|---|---|---|---|
| Redis 挂 | **fail-closed**：「活动火爆，请稍后」 | 回源 DB（带保护） | 本地缓存读，写入暂缓 |
| MySQL 挂 | 仍可接单，返回「领取处理中」 | Caffeine L1 顶住 | 不受影响 |
| MQ 挂 | 直接 fail | 不受影响 | 异步写暂缓，靠对账 |

### 语义决策一：秒杀降级只能是"拒绝"，不能是"放行"

Mode A 的不超卖证明唯一依赖 Redis Lua 的原子扣减。Redis 挂了还放行 = 拆掉正确性屏障，直接超卖。
**限流器可以 fail-open（多放请求只是压力问题），业务层必须 fail-closed。**

### 语义决策二：DB 熔断打开时返回"处理中"，不是"成功"

当前 `VoucherOrderServiceImpl:309` 在 Lua 返回 0 时直接 `Result.ok(orderId)`，但此刻订单尚未落库
（Redis 预扣成功 + 事务消息已提交）。DB 持续不可用时订单靠对账补，对账也失败则用户看到
"领取成功但订单消失"。降级设计：`dbBreaker` 打开时返回 `code=ORDER_PROCESSING`。
**说谎的降级比明确的报错更糟。**

### R4J fallback 硬规则

1. `fallbackMethod` 签名 = 原方法参数 + 末尾 `Throwable`，返回类型一致；不匹配启动时报
   `NoSuchMethodException`（早失败，好事）。
2. fallback 里**不能再调同一个故障依赖**（Redis 挂了 fallback 又读 Redis = 二次雪崩）。
3. fallback 抛出的异常不会再套一层 fallback，直接冒泡 → fallback 内部必须自己兜到不能再失败。
4. fallback 必须打点（`ResilienceMetrics`），否则降级是隐形的。

## 8. 重试策略

- **只加在只读幂等路径**：缓存查询（2 次、间隔 100ms、指数退避），写路径一律不加。
- **MQ 发送不叠 R4J Retry**：client 已有 2 次重试 + 事务回查 + 对账补单三层兜底，
  再叠应用层重试会放大故障期无效流量（重试风暴）。
- 乐观锁冲突、Redis 连接瞬时抖动这类**可重试且幂等**的场景才配 Retry。

## 9. 兜底阶梯

正常 → 一级·切链路（回源 DB）→ 二级·兜底值（过期缓存/空值）→ 三级·关功能（非核心摘除）
→ 最终兜底（语义化错误 + traceId + 降级打点）。

每降一级都要给新链路配保护：**回源 DB 的同时必须限流隔离，否则降级本身就是雪崩放大器**。

主动降级开关（大促前手动摘功能）与运行时自动降级互补。项目无配置中心，只做 1–2 个
`hmdp.degrade.*` 开关（关闭非核心计数、关闭缓存异步重建），不做全套开关中心（YAGNI）。

## 10. 埋点

新增 `ResilienceMetrics`（`com.hmdp.observability`），订阅 R4J 事件：
`onError / onStateTransition / onCallNotPermitted / onRetry / fallback`。

遵守既有 tag 红线：**只放 `breaker` / `kind` / `result` 这类有限枚举**，
orderId / userId / traceId 一律不做 tag。

## 11. 验证方式

用真实故障演练，不靠单测模拟：

1. `docker compose stop redis` → 观察秒杀接口从"60s 挂起"变成"800ms 返回语义化错误"
2. `curl -s localhost:8080/actuator/prometheus | grep resilience` → 熔断状态翻转、降级计数
3. Grafana 看板补一块「熔断与降级」面板
4. 恢复后观察自动恢复（半开 → 闭合）

## 12. 分阶段计划

| 阶段 | 内容 | 验收 |
|---|---|---|
| **P0** | 异常体系 + 全局异常处理器分层 | `mvn compile` 通过；业务异常不进熔断计数 |
| **P1** | Redis 超时收敛 + `redisBreaker` + 秒杀信号量舱壁 | `docker compose stop redis` 后 800ms 内返回语义化错误 |
| **P2** | `mqBreaker` / `dbBreaker` + 消费端自定义重试上限 + 落库降级语义 | MQ 挂时秒杀快速失败；DB 挂时返回「处理中」 |
| **P3** | `ResilienceMetrics` 埋点 + Grafana 面板 + 故障演练文档 | 全量降级动作可见；演练步骤可复现 |
