# 简历正文（精简版 · 直接粘贴）

**HMDP-Pro｜高并发领券系统**（个人项目 · Java 后端）
`Spring Boot 2.3 · MySQL 8 · Redis 7 · RocketMQ 4.9 · Redisson · Caffeine · Resilience4j · Prometheus + Grafana · OpenResty`
`github.com/clerimia/hmdp-pro`

> 定位一句话（自我介绍用）：在黑马点评教学项目基础上，以校园餐饮优惠场景为载体，按接近生产的方式补齐了高并发领券链路（秒杀技法实现）缺失的分层限流、分布式 ID、多级缓存一致性、熔断降级、全链路观测与对账兜底六块能力。

---

**1. 高并发领券链路（秒杀技法）**：入口三层削峰——网关 OpenResty 令牌桶限集群总入口，应用层 Redis ZSET 滑动窗口按 `userId` 限流（领券与查询独立配额），入口信号量舱壁许可耗尽立即拒；写路径以改造版 UidGenerator（RingBuffer 预生成 65536 个 ID、无锁 CAS 取号、63bit 位分配、epoch 前移）在预扣前生成订单号，再由 RocketMQ 事务消息串联 Lua 原子扣减与异步落库，落库以订单主键幂等、先 insert 再扣库存同事务；兜底为消费重试（上限 5 次）+ 死信告警 + 分钟级对账（补单 → 库存重算）。实测 30 并发领 10 份库存零超卖零重复，停 MySQL 后 18 笔订单恢复 90 秒内全部追平。

**2. 多级缓存**：Caffeine → Redis（逻辑过期）→ MySQL 三级读链路，过期命中返回旧值并由 Redisson 锁异步重建，互斥锁有界等待防击穿、空值标记防穿透、TTL 抖动防雪崩；写回前比对 `update_time` 版本丢弃脏快照，更新走事务提交后失效两级缓存，以 3 倍逻辑 TTL 的物理 TTL 保险丝兜底。

**3. 容错降级**：超时基线收敛为 Redis 800ms、Hikari 连接 3s，分布式锁显式 lease 禁用 watchdog；熔断器按依赖拆分为 `redisBreaker` / `mqBreaker` / `dbBreaker` 并忽略业务异常与舱壁拒绝；读路径降级回源 DB 过独立舱壁，写路径 fail-closed，落库降级返回"处理中"而非伪成功。

**4. 全链路观测**：自研 traceId 串联 HTTP / 线程池 / MQ 发送 / MQ 消费四类边界；指标走 Micrometer + Prometheus，秒杀成败单指标 + `reason` 枚举分维度、缓存命中分 L1/L2/DB 三级打点，订阅 Resilience4j 事件流暴露熔断状态与降级量，死信队列配告警，共 13 个 Grafana 面板。

---

## 备注（不贴进简历）

- **追问准备在 `docs/resume-hmdp-pro.md`**：上面每个技术点被追问"为什么这么做 / 参数怎么定的"时，那里有答法。这一版只留技术名词，是为了让简历一眼扫得完。
- 所有参数与实测数字均已对着源码核实。
- **仍缺 QPS / P95 类压测数据**，正文因此没写。
