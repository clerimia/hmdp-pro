# 可观测性：traceId 链路追踪 + Prometheus 埋点

## 1. 模型：为什么代码里没有「上报」

Prometheus 是 **pull 模型**，应用侧只有两件事：

1. **埋点** — 事件发生时内存计数器自增（`LongAdder`，纳秒级）；
2. **暴露** — `/actuator/prometheus` 端点输出当前所有指标的快照文本。

剩下的「采集」由 Prometheus server 按 `scrape_interval` 定时来拉。没有逐条上报动作，
也因此埋点组件故障不会拖垮业务线程——秒杀这种高频路径如果逐条 push 到远端，
一次网络 IO 就会让埋点本身变成性能瓶颈和故障点。

三者的分工（排查顺序也是这个顺序）：

| 手段 | 回答什么问题 | 本项目实现 |
| --- | --- | --- |
| Metrics | 「异常了吗」聚合趋势、告警 | Micrometer → `/actuator/prometheus` |
| Logs | 「这一笔为什么失败」明细下钻 | logback，每行带 `[traceId]` |
| Trace | 「一次请求经过了哪些环节」链路串联 | 自研 traceId + MDC（无 span 树） |

## 2. traceId 的三个边界

MDC 底层是 `ThreadLocal`，跨线程和跨进程都会断，所以每个边界都要显式接管一次。
四类边界全部复用 `TraceContext` 的同一套动作：**取或生成 → put → finally clear**。

| 边界 | 代码位置 | 载体 |
| --- | --- | --- |
| HTTP 入口 | `observability/TraceIdFilter`（注册见 `config/ObservabilityConfig`，order = HIGHEST_PRECEDENCE） | 请求头 `X-Trace-Id` |
| 线程池 | `observability/MdcTaskDecorator` → `traceAwareExecutor` | MDC 快照（`submit()` 时刻捕获） |
| MQ 发送 | `observability/MqTraceCarrier#inject`（3 个 send 方法） | 消息 `properties` |
| MQ 消费 / 事务回查 | `mq/OrderMQConsumer`、`mq/SeckillTransactionListener#checkLocalTransaction` | 消息 `properties`（重试带 `-r{n}` 后缀） |

**包结构约定**：`com.hmdp.observability` 只放能力类（上下文门面、埋点门面、边界适配器），
不含 `@Configuration`；装配统一放 `com.hmdp.config.ObservabilityConfig`，与项目其他
`CaffeineConfig` / `RedissonConfig` 等保持一致。业务侧注入线程池用
`@Qualifier(ObservabilityConfig.TRACE_AWARE_EXECUTOR)`，不要自建线程池。

两个关键约定：

- **「有就复用，没有才生成」**：入口 Filter 和 MQ 消费端都是这个逻辑，链路才能跨进程串起来。
- **`finally` 里必须 clear**：Tomcat 工作线程、MQ 消费线程、缓存重建线程全部池化复用，
  不清理会让下一个请求打出上一个请求的 traceId（日志串号，比没有 traceId 更难排查）。

一个容易漏的点：`RocketMQProducer` 的事务回查线程是 Broker 发起的，
与发送线程不是同一个线程，且没有「提交方」可继承上下文，只能从消息 properties 还原。

## 3. 指标与关键事件

指标名用点分命名，Micrometer 按 Prometheus 约定自动转换：
Counter `hmdp.seckill.result` → `hmdp_seckill_result_total`；
Timer `hmdp.seckill.latency` → `hmdp_seckill_latency_seconds_*`。
（所以 Timer 名不要自己加 `_seconds`，否则会变成 `..._seconds_seconds`。）

| 指标 | 类型 | tag | 埋点位置 |
| --- | --- | --- | --- |
| `hmdp.seckill.request` | Counter | `mode` | `SlidingWindowInterceptor`（限流**之前**，用于算被限流比例） |
| `hmdp.seckill.result` | Counter | `mode` `result` `reason` | 秒杀 A/B 各分支、被限流拦截的请求 |
| `hmdp.seckill.latency` | Timer | `mode` `result` | 秒杀方案 A/B 方法耗时 |
| `hmdp.ratelimit.fallback` | Counter | `strategy` | 限流器自身异常 → `fail_open` 放行 |
| `hmdp.order.consume` | Counter | `tag` `result` | MQ 消费 CREATE / TIMEOUT 结果 |
| `hmdp.order.timeout_send_error` | Counter | — | 超时关单延迟消息发送失败（已记入 Redis 重试集合，对账任务重发） |
| `hmdp.order.dead_letter` | Counter | — | 订单消息超重试上限落入死信 topic（等待对账/人工介入） |
| `hmdp.cache.rebuild` | Counter | `result` | 缓存异步重建结果 |
| `hmdp.cache.hit` | Counter | `level`(l1/l2/db) | 多级缓存命中层级分布 |

`reason` 取值封闭在 `SeckillMetrics.Reason` 枚举里：
`success` / `stock_out` / `repeat` / `rate_limited` / `mq_send_error` / `db_degraded` / `system_error`。
（`db_degraded` = dbBreaker 打开/半开时入口诚实返回「下单处理中」，P2 落库降级语义。）

### tag 使用红线

**绝不能用 `orderId` / `userId` / `traceId` 当 tag。** Prometheus 里每个唯一的 tag 组合都是一条
独立时间序列，高基数值会同时打爆采集端内存和查询。单笔明细用带 traceId 的日志查，不归指标管。

## 4. 埋点纪律

1. **埋在决策点，不埋在 IO 前后**：每个 `return` / `throw` 分支对应一个事件。
   实现手法是「reason 变量贯穿分支 + finally 统一落定」，保证任何路径都不漏统计。
2. **成败共用一个指标名**，用 `reason` tag 区分，避免指标数量爆炸，Grafana 上也只需一张图。
3. 空 / null 的 tag 值统一兜底成 `unknown`，奇数个 tag 直接抛异常（宁可启动期就炸，
   也不要静默产生畸形指标）。

## 5. 扩展点

| 扩展点 | 现在 | 怎么换 |
| --- | --- | --- |
| ① traceId 生成策略 | `UuidTraceIdGenerator`（32 位无横线 UUID） | 注册自己的 `TraceIdGenerator` Bean，`config/ObservabilityConfig` 里挂了 `@ConditionalOnMissingBean` 会自动让位 |
| ② 跨进程载体 | `MqTraceCarrier`（RocketMQ properties）/ Filter（HTTP header） | 换 Kafka、加 gRPC 时新增 carrier，`inject/extract` 签名不变，业务调用点不动 |
| ③ 埋点后端 | `MicrometerRecorder`（Prometheus） | 实现 `ObservabilityRecorder` 接口（换 OTel 只改实现类）；压测时 `hmdp.observability.metrics.enabled=false` 自动切 `NoOpRecorder` |
| ④ 事件集合 | `SeckillMetrics` / `CacheMetrics` | 加方法即可，指标名与合法 tag 值集中在这两个类里 |

业务代码只依赖 `ObservabilityRecorder` 和语义化的 `SeckillMetrics` / `CacheMetrics`，
换任何一层实现都不用改调用点。

## 6. 验证

```bash
# 1. 日志带 traceId：任意请求后看控制台，每行形如
#    10:22:33.123 [http-nio-8081-exec-1] [5f2c...] INFO  c.h.service.impl.VoucherOrderServiceImpl - ...

# 2. 响应头回传 traceId（前端报障可直接贴这个 id）
curl -i http://localhost:8081/shop/1 | grep -i x-trace-id

# 3. 指标端点
curl -s http://localhost:8081/actuator/prometheus | grep hmdp_
```

压测后应能看到（示例）：

```
hmdp_seckill_result_total{application="hmdp-pro",mode="A",reason="stock_out",result="fail"} 42.0
hmdp_seckill_latency_seconds_count{application="hmdp-pro",mode="A",result="success"} 158.0
hmdp_ratelimit_fallback_total{application="hmdp-pro",strategy="fail_open"} 3.0
```

## 7. 本地采集栈（Prometheus + Grafana，可选）

镜像与配置都在仓库里，两条命令起栈：

```bash
docker compose up -d prometheus grafana   # 只起观测栈；加 mysql redis 起全套
docker compose down                        # 停（数据卷保留：prometheus-data / grafana-data）
```

| 入口 | 地址 | 说明 |
| --- | --- | --- |
| Prometheus | http://localhost:9090 | `/targets` 看抓取状态 |
| Grafana | http://localhost:3000 | `admin` / `admin`，面板在 **hmdp-pro** 文件夹 |

三个关键点：

1. **Prometheus 抓的是宿主机上的应用**，不是容器内的 —— 配置里写的是
   `host.docker.internal:8081`（靠 `extra_hosts: host-gateway` 解析）。
   所以**应用要在宿主机先跑起来**；应用没起时 `/targets` 里显示 DOWN 是正常的，不是配置错了。
2. **面板自动导入**，不用手点：Grafana 的 provisioning 会加载
   `docker/grafana/provisioning/`（数据源）和 `docker/grafana/dashboards/hmdp-seckill.json`
   （8 个面板：QPS、成功率、结果分布、耗时 P95、限流拦截/兜底、MQ 消费、缓存命中层级、重建结果）。
3. **改了 `prometheus.yml` 不用重启容器**：
   `curl -X POST http://localhost:9090/-/reload`（已开 `--web.enable-lifecycle`）。

PromQL 速查：

| 想看什么 | PromQL |
| --- | --- |
| 秒杀进入 QPS | `sum(rate(hmdp_seckill_request_total[1m])) by (mode)` |
| 成功率 | `sum(rate(hmdp_seckill_result_total{result="success"}[1m])) / sum(rate(hmdp_seckill_result_total[1m]))` |
| 失败原因分布 | `sum(rate(hmdp_seckill_result_total[1m])) by (reason)` |
| 耗时 P95 | `histogram_quantile(0.95, sum(rate(hmdp_seckill_latency_seconds_bucket[1m])) by (le, mode))` |
| 限流被拦截 / 兜底 | `sum(rate(hmdp_seckill_result_total{reason="rate_limited"}[1m]))` / `sum(rate(hmdp_ratelimit_fallback_total[1m])) by (strategy)` |
| 缓存命中层级 | `sum(rate(hmdp_cache_hit_total[1m])) by (level)` |

## 8. 已知取舍

- **没有 span 树**：本方案只做 traceId 串联，没有父子 span 和耗时瀑布图。
  真上微服务就换 OpenTelemetry / SkyWalking（字节码增强自动埋点），
  那时 `TraceContext` 是唯一的改动点。
- **无 span 内的明细事件流**：关键事件的明细靠结构化日志 + traceId 检索，
  不上 ELK 的话只能用 `grep` 按 traceId 捞。
- **限流器 fail-open 是行为变更**：`SlidingWindowInterceptor` 原本 Redis 异常会直接抛出（500），
  现改为放行并计 `hmdp_ratelimit_fallback_total{strategy="fail_open"}`。
  依据是本项目既定的 fail-open/fail-closed 哲学：限流是保护手段，业务层（库存、一人一单）仍然
  fail-closed。
- **链路从 Java 入口开始，网关（OpenResty）不在链路内**：网关只做令牌桶限流，
  请求头的 `X-Trace-Id` 由上游客户端/网关带来时会被复用，否则由 `TraceIdFilter` 生成。
  代价是网关自身耗时不进入任何指标；要覆盖就在 OpenResty 侧生成 `X-Trace-Id` 并透传，
  因为 Filter 本身就是「有就复用」，Java 代码一行都不用改。
- **网关不再缓存业务数据，顺带消掉一段链路盲区**：此前商铺详情命中 `lua_shared_dict`
  时直接在网关返回，请求根本不进 Java —— 既没有日志、没有 traceId，也无法被 Canal 驱逐
  通知到，是彻底的观测黑洞兼一致性黑洞。现在读缓存全部收回 Java 多级缓存，
  命中层级由 `hmdp.cache.hit{level}` 观测。
