# 秒杀链路优化 · 交接文档

> 最后更新 2026-09-04 18:30。WIP commit `41f7c81` 为上一轮成果。
> **第二轮：查询接口收尾 + 端到端验证（挖出 broker「能发不能收」的环境 bug）。**
> **第三轮：混沌测试 + 索引审计，见 [`docs/chaos-test-report.md`](./chaos-test-report.md)。**
>
> **一句话状态：秒杀 + 入库链路端到端跑通，混沌测试通过；支付链路按怡霖要求跳过。**

---

## 1. 本轮做了什么

| # | 事项 | 结果 |
|---|---|---|
| 1 | `getSeckillResult` 改造（登录校验 / 归属下压 SQL / 空值标记） | 完成并验证 |
| 2 | `MvcConfig` 把 result 查询路径挂进限流拦截器 | 完成并验证（此前限流是空话） |
| 3 | `RedisConstants` 加 `SECKILL_QUEUE_NULL_TTL_SECONDS = 10` | 完成 |
| 4 | 端到端验证（原 Task #3） | 用例 1/2/3/4/5/9/10 + 限流 全通过 |
| 5 | **修复 broker「能发不能收」的环境 bug** | 见第 3 节，本轮最有价值的发现 |
| 6 | 复核「store 改挂载卷」方案 | **结论：不可行**，见第 4 节 |

**支付链路（原 Task #7/#8 的 `getPayResult` / `pay/result/{orderId}`）本轮跳过**，代码里没有落地 `PayStatus`、`getPayResult`、controller 端点。
`SlidingWindowInterceptor` 的 pay 分支与 yaml 的 `pay-window-ms / pay-max-requests` 已就位但未生效（`MvcConfig` 没挂 path），待该接口实现时补一行即可。

---

## 2. 本轮代码改动

### 2.1 `VoucherOrderServiceImpl#getSeckillResult`（重写）

三级短路，越靠前越便宜：

1. **Redis 排队状态命中 → 直接返回，不碰 DB**
2. **未命中 → 查订单表**，归属校验下压成 SQL 条件
3. **查无此单 → 回写短 TTL 空值标记**（10s），让同一个不存在的 orderId 在 TTL 内不再穿透

**第 ① 步不校验归属是有意的取舍**（代码注释里写明）：排队状态的 value 只有 `WAITING/SUCCESS/FAIL_*`，无敏感信息；一校验归属就得先查 DB，"不打 DB"的意义就没了。真正让 orderId 不可枚举的是它本身——UidGenerator 出的 63 位雪花 ID（28bit 时间戳 + 22bit workerId + 13bit 序列号），猜不出有效区间。

**第 ② 步必须校验归属**：订单表有 `userId`/`createTime` 等敏感字段，且撞库收益大于成本。归属判断下压进 WHERE，让「查不到」与「不是你的」返回完全相同的 `NOT_FOUND`，不给枚举探测任何信号。

### 2.2 `MvcConfig`

```java
registry.addInterceptor(slidingWindowInterceptor)
        .addPathPatterns("/voucher-order/seckill/{id}",
                "/voucher-order/seckill/result/{orderId}")
        .order(2);
```

**这一步此前漏了，导致拦截器里的 result 分支永不执行**——「查落库 10 次/秒」的限流形同虚设。

### 2.3 `RedisConstants`

`SECKILL_QUEUE_NULL_TTL_SECONDS = 10L`。只防**同一个**伪造 orderId 的反复查询，换号靠限流挡；TTL 取 10s 而非分钟级，是为了误判能自愈（排队状态写入失败 + 落库未完成时会被误标 `NOT_FOUND`，短 TTL 让用户等几秒再查就对）。

---

## 3. 端到端验证结果（全部实测）

### 3.1 环境

- 中间件：mysql / redis / rocketmq-namesrv / rocketmq-broker 全部 `Up (healthy)`
- 应用：8081，`spring-boot:run -Dspring-boot.run.profiles=local`，日志 `target/app.log`

### 3.2 用例结果

| # | 操作 | 预期 | 实测 |
|---|---|---|---|
| 1 | POST `/voucher-order/seckill/11`（未开始） | 活动尚未开始 | ✅ `code=1007` |
| 2 | POST `/voucher-order/seckill/12`（已结束） | 活动已结束 | ✅ `code=1008` |
| 3 | GET `seckill/result/{伪orderId}` ×2 | 第二次不打 DB | ✅ 均 `NOT_FOUND`；MySQL general_log 实测 **2 次查询只打 1 次 DB**，Redis 标记 TTL=10 |
| 4 | POST `/voucher-order/seckill/13`（进行中） | 下单→落库 | ✅ `Result.ok(orderId)`；`WAITING`→`SUCCESS`；DB 落库 `732461317478219776 user=7 voucher=13 status=1`；`stock:13` 50→47 |
| 5 | 同用户再抢券 13 | 不能重复下单 | ✅ `code=1004` |
| 9a | 本人查已落库订单（强制走 DB 分支） | SUCCESS | ✅ `SUCCESS + orderStatus=1` |
| 9b | **换用户 B 查同一 orderId** | NOT_FOUND | ✅ `NOT_FOUND`（归属校验生效） |
| 10 | 对账任务（60s 一轮） | 券 12 库存收敛 | ✅ 静默跳过（DB 98 / Redis 98 一致） |
| 限流 | 1 秒内突发 15 次 result 查询 | 前 10 通过 | ✅ `200×10 + 429×5`，应用日志同步出现 5 条限流 WARN |

### 3.3 预热与时区（顺带验证）

- 券 11（未开始）首次请求触发预热：`seckill:meta:11` 写入 + `seckill:stock:11 = 100` 回填；返回 1007
- 券 12（Redis 库存已存在）只补 `meta`，**不覆盖** `stock`（`setIfAbsent` 语义正确）
- 毫秒时间戳换算回本地时区与 DB 完全一致：
  `1788558890000 → 2026-09-05 05:54:50 +08:00`（券 11 begin）
  **时区 bug 确认已修复**（若错 8 小时，券 11 会在当晚 21:54 被判成"已开始"）

### 3.4 🔴 本轮最有价值的发现：broker「能发不能收」

**现象**：下单全部返回成功、Redis 预扣成功、`seckill:txn` 标记写入，但**订单永远不落库**，`WAITING` 不变 `SUCCESS`。

**排查链**（每一步都有证据，值得记方法论）：

| 步骤 | 证据 | 结论 |
|---|---|---|
| 1 | 应用日志无任何消费者痕迹（日志已确认为实时） | 不是日志缓冲 |
| 2 | `seckill:txn:{orderId} = 1` 存在 | Lua 执行成功，卡在 MQ→消费者 |
| 3 | `consumerProgress`：`order-seckill-topic` broker offset=1/2，consumer offset=0，**Diff 只增不减** | 消息已 COMMIT，消费者没消费 |
| 4 | `consumerConnection`：客户端在册、订阅 `CREATE \|\| TIMEOUT` 正确 | **不是没连上 broker** |
| 5 | ConsumeQueue 二进制：`76 f8 94 fc` = `hash("CREATE")` = 订阅 codeSet 中的 1996002556 | **不是 tag 过滤** |
| 6 | 重启应用后 Diff 依旧 | 不是初始化时序 |
| 7 | `mqadmin queryMsgByOffset` 稳定复现：<br>`NoClassDefFoundError: Could not initialize class org.apache.rocketmq.store.StoreUtil`<br>`at DefaultMessageStore.checkInDiskByCommitOffset` | **broker 读路径坏了** |
| 8 | broker.log / store.log 对此**零记录**（异常被 catch 后只作为响应返回） | 日志查不到 ≠ 没发生 |

**根因**：`apache/rocketmq:4.9.7` 镜像自带 JDK 8u372，在 Docker Desktop(WSL2) 的 cgroup v2 下 `CgroupV2Subsystem` 探测抛 NPE → `StoreUtil` 静态初始化失败 → 此后每次读消息都抛 `NoClassDefFoundError`。
**只影响读（消费者），不影响写（生产者）**，所以表现为"能发不能收"——极具迷惑性。

**修复**：`docker-compose.yml` 的 namesrv 与 broker 的 `JAVA_OPT_EXT` 加 `-XX:-UseContainerSupport`（关闭容器感知跳过该探测；堆已用 `-Xms/-Xmx` 显式固定，无副作用）。
**验证**：修复后 `queryMsgByOffset` 不再报错，下单 3s 内落库，`Diff → 0`。

### 3.5 🟡 复现出的 P2 缺陷：进行中券丢单无人兜底

broker 重启（tmpfs，消息全丢）导致券 13 有 **2 笔已预扣但消息丢失**的订单。

| 项 | 值 |
|---|---|
| DB `stock` | 49 |
| Redis `seckill:stock:13` | 47 |
| 差额 | **2 份库存被扣但订单不存在，且永远回不来** |

原因：`supplementMissingOrders` **只对已结束的券跑**（这是当初为了避免"开始后 Redis 是真值、DB 落后"导致超卖而做的保守设计）。券 13 活动进行中，对账不碰它 → 这 2 份库存凭空蒸发，用户查订单得到 `NOT_FOUND`（排队状态 5 分钟 TTL 过期后）。

这是**真实存在的少卖路径**，不只是理论问题。修的方向（未做）：对进行中券补单时，只补 `seckill:order:{id}` 集合里有、而 DB 里没有的 userId，且**只补订单不回补库存**（库存已被 Lua 扣掉，回补即超卖）。

---

## 4. 环境备忘（本轮更新）

- **`docker compose`（空格）本机不可用**，必须 **`docker-compose`（横线，v2.35.1-desktop.1）**；引擎 Docker 28.1.1
- **`rocketmq-broker` 的 store 只能用 tmpfs，挂载卷一律退出 253**。本轮在两个新前提下各验证一次，都失败，**别再试了**：
  - 假设 A「是 JDK 8u372 cgroup v2 bug」→ 加了 `-XX:-UseContainerSupport` 后命名卷**仍旧 253**，而 tmpfs 正常 ⇒ 两者是独立问题
  - 假设 B「是卷属主权限」→ 镜像里根本没有 `/home/rocketmq/store`，挂空卷后由 root 创建；`chown 3000:3000` 后**仍旧 253**
  - 剩余最可能原因：RocketMQ 对 commitlog 的 mmap 在 Docker Desktop 的挂载卷后端上不被支持
- **tmpfs 的代价（重要）**：broker 重启后 topic 元数据全丢，topic 要等**首次发送**才被 `autoCreateTopicEnable` 自动创建 → 消费者启动时 topic 不存在 → **重启后前几单消费不到**（本轮实测：下单后等了约 60s 客户端刷新路由才追平）。broker 重启后记得重启应用。
- **broker 重启后必须手动预建 topic**：事务消息发往 `RMQ_SYS_TRANS_HALF_TOPIC`，**不会触发 autoCreate**，所以重启应用也救不回来，会一直报 `No route info of this topic`。必须：
  ```bash
  docker exec hmdp-pro-rocketmq-broker-1 sh -c \
    "/home/rocketmq/rocketmq-4.9.7/bin/mqadmin updateTopic -n rocketmq-namesrv:9876 -b broker-a -t order-seckill-topic"
  ```
- **broker 秒退 253 且 `docker logs` 只显示 `boot success` 时**：真凶是 tmpfs 属主为 root、rocketmq 用户（uid 3000）写不进 `/home/rocketmq/store/lock`。compose 里已用 `tmpfs: - /home/rocketmq/store:uid=3000,gid=3000` 固定。异常只在容器内 `broker.log` 里，用 `docker cp` 取出来看。
- MySQL：宿主机 **3307**，root/123456，容器 TZ=Asia/Shanghai
- Git Bash 下用 **`mvn.cmd`**；改了 `db/` 下 SQL 必须 `docker-compose down -v` 才会重跑
- `application-local.yaml` 已 gitignored；git push 间歇性连不上 github，**直接重试**即可

### 4.1 本轮造的测试数据（可安全清理）

券 13 是为跑通「进行中下单」造的，含 2 份 orphan 库存（见 3.5）。要清掉：

```sql
DELETE FROM hmdp.tb_voucher_order WHERE voucher_id = 13;
DELETE FROM hmdp.tb_seckill_voucher WHERE voucher_id = 13;
DELETE FROM hmdp.tb_voucher WHERE id = 13;
```
```bash
docker exec hmdp-pro-redis-1 redis-cli DEL seckill:stock:13 seckill:order:13 seckill:meta:13
```

另：`seckill:timeout:retry` 的重发机制已整体删除（closeTimeoutOrders 的 DB 扫描全量覆盖其功能），但运行中的 Redis 里可能残留旧 key（内容是历史失败的 orderId，已无代码读取），一次性清理即可：

```bash
docker exec hmdp-pro-redis-1 redis-cli DEL seckill:timeout:retry
```

另：券 10 的 Redis 库存是 **0**、DB 是 **198**，是上一轮压测残留（不是本轮产生）。券 10 活动 09-05 05:54 结束，届时对账会自动重算修正；想立刻修就手动 `SET seckill:stock:10 198`（活动进行中手工改库存有超卖风险，谨慎）。

---

## 5. 遗留事项

1. **支付链路未做**：`getPayResult` / `pay/result/{orderId}` / `PayStatus`。拦截器的 pay 分支与 yaml 配额已就位，实现后往 `MvcConfig` 补一个 path 即生效
2. **前端降级误报成功**：`Result.fail(ORDER_PROCESSING)` HTTP 仍是 200，前端 `.then` 里无脑提示"抢购成功，订单id：[object Object]"。需按 `success/code` 分支展示——待怡霖拍板
3. **前端无订单页/支付入口**：`shop-detail.html` 的 `seckill()` 注释即"支付功能TODO"；后端也没有订单列表接口
4. **P2 未做**：
   - 进行中券丢单无人兜底（**已复现，见 3.5，优先级最高**）
   - `cancelTimeoutOrder` 回补库存非原子
   - 限流 Lua 每次 `UUID.randomUUID()`
5. **混沌测试新增的待决策项**（详见 `chaos-test-report.md` 第 4 节）：
   - Redis 故障返回 401，文案误导（应区分「无 token」与「Redis 不可用」）
   - 关单回补不修正 Redis/DB 存量漂移，只能等活动结束后对账重算
   - `NOT_FOUND` 语义三义（不存在 / 非本人 / 未落库），极端情况会让用户误以为失败
   - dbBreaker 学习期（10 次调用）内下单仍返回成功
5. **前端轮询策略**（快-慢-熔断 + 抖动 + 首次延迟 300~500ms）已定稿未实现，依赖支付接口先完成
6. 轮询参数备忘：1~3 次 500ms / 4~10 次 2s / 之后 5s / 总超时 30~60s 后停止并提示兜底文案；首次延迟 300~500ms + ±20% 抖动

---

## 6. 关键决策速查（面试表述用）

1. **幂等键 = 订单主键 orderId**：消息重投撞主键、事务回滚、库存不扣；`uk_user_voucher` 只是 EARLY 档的兜底
2. **先 insert 再扣库存**：由 1 推出的顺序，反向则重投会"扣了再补"
3. **库存回填只在活动开始前安全**：开始后 Redis 是真值，DB 回填 = 超卖
4. **轮询被库存封顶**：限流防的是伪造 orderId 穿透，不是轮询洪峰——量级论证先行，别上来就堆防线
5. **时区三处必须一致**：容器 TZ / JDBC serverTimezone / JVM systemDefault，任一不一致 timestamp 就漂移
6. **错误文案不区分越权原因**：防订单号枚举探测；区分信息只进日志
7. **三道防线分得清谁防谁**：前端退避防"自己人无意识放大"（可绕过），服务端限流防一切（真防线），两者不是替代关系
8. **查询接口的分级短路**：Redis 状态 → 归属下压 SQL → 空值标记；每一步的"不做什么"和"做什么"同样重要（第 ① 步故意不校验归属，第 ② 步必须校验）
9. **"能发不能收"要怀疑 broker 读路径**：生产者成功、消费者静默失败，且 broker 日志零记录时，用 `mqadmin queryMsgByOffset` 直接探读路径，比翻日志快
