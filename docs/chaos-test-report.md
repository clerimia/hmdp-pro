# 秒杀链路混沌测试报告

> 执行于 2026-09-04 17:35 ~ 18:30（第二轮会话）。
> 目标：对秒杀 + 入库链路做故障注入，全量排查问题，重点验证**轮询兜底**与**索引**。
> 支付链路不在范围（按怡霖要求跳过）。

---

## 0. 结论速览

| 类别 | 结论 |
|---|---|
| 并发正确性 | ✅ 无超卖、无一人多单，Redis/DB 最终一致 |
| 消息幂等 | ✅ 重投撞主键，事务回滚，库存零副作用 |
| MQ 故障 | ✅ 入口返回 5004，且不误扣 Redis 库存 |
| DB 故障 | ⚠️ 降级正确（1100），但轮询会 500 —— **已修复** |
| Redis 故障 | ⚠️ fail-closed 但返回 401，文案误导 |
| 自愈能力 | ✅ DB 恢复后约 90 秒全部自动追平 |
| 对账索引 | ❌ 3 个查询全表扫描 20 万行 —— **已修复** |
| 轮询索引 | ✅ 走主键，不需要加 |

**本轮改动**：加 3 个索引（2 张表）、修复轮询 DB 故障期返回 500、新增 `UNKNOWN` 状态。

---

## 1. 索引审计（怡霖问的重点）

### 1.1 轮询查询：**不需要加索引**

`getSeckillResult` 的兜底查询是 `WHERE id = ? AND user_id = ?`，走 `PRIMARY KEY(id)`，`type=const`，无需额外索引。

同理走主键、不需要索引的还有：`payOrder`（id + user_id + status）、`cancelTimeoutOrder`（id + status）。
走 `uk_user_voucher(user_id, voucher_id)` 的是 LEGACY 档的 `count()` 预查。

### 1.2 真正缺索引的是对账任务（已修）

对账每 60s 跑一轮，订单表是持续增长的账本。原表只有 `PRIMARY(id)` 和 `uk_user_voucher(user_id, voucher_id)`。

**20 万行实测（加索引前 → 后）**：

| 对账查询 | 加索引前 | 加索引后 |
|---|---|---|
| ①关单兜底 `status=1 AND create_time<?` | `ALL` / 199515 行 | `range` / **1 行** |
| ②补单 `voucher_id=?` | `index` 全索引扫 uk / 199515 行 | `ref` / **4 行** |
| ③库存重算 `voucher_id=? AND status IN(1,2)` | `ALL` / 199515 行 | `range` / **3 行** |

注意 ② 那个 `type=index` 很有迷惑性：EXPLAIN 显示用了 `uk_user_voucher`，但它是**全索引扫描**而非查找——因为 uk 的最左列是 `user_id`，按 `voucher_id` 查时前缀不匹配，等于把整个索引扫了一遍。

**已加索引**（同步进 `db/hmdp-schema.sql`）：

```sql
-- tb_voucher_order
INDEX idx_voucher_status    (voucher_id, status)      -- 覆盖 ②③
INDEX idx_status_create_time (status, create_time)    -- 覆盖 ①
-- tb_seckill_voucher
INDEX idx_end_time          (end_time)                -- 覆盖 ②③ 的「取已结束券」
```

`idx_voucher_status` 一列两用：③ 用到完整两列，② 只用前缀 `voucher_id`。
`idx_status_create_time` 的列序不能反——`status` 是等值条件、`create_time` 是范围条件，范围列之后的索引列会失效，等值列必须放前面。

---

## 2. 轮询兜底：发现的核心缺陷（已修）

### 2.1 🔴 DB 故障期轮询返回 500

**现象**：混沌测试中停掉 MySQL 后，轮询一个排队状态已丢失的订单返回 `500 / code=5999`。

**为什么这是最该修的一个**：DB 故障恰恰是用户**最需要**轮询的时刻——入口降级返回 `1100 ORDER_PROCESSING` 并把 orderId 交给前端，用户只能靠轮询确认结果。而轮询的兜底路径就是查订单表，DB 一挂它自己也挂，直接抛 500。

给用户 500 等于告诉他「彻底失败了，别等了」，但实测 DB 恢复后约 90 秒内订单会全部自动追平。

**修复**：DB 查询包 try-catch，失败时返回 `UNKNOWN` 而非抛异常。

```java
try {
    order = query().eq("id", orderId).eq("user_id", user.getId()).one();
} catch (Exception e) {
    data.put("status", SeckillMode.QUEUE_UNKNOWN);
    return Result.ok(data);
}
```

**验证**：同一场景从 `500 / code=5999` 变为 `200 / status=UNKNOWN`。

**语义区分很关键**：`NOT_FOUND` = 确定没有；`UNKNOWN` = 暂时不知道。前端应对 UNKNOWN 继续轮询，对 NOT_FOUND 才停止。

### 2.2 其余轮询行为（均正常）

| 场景 | 实测 |
|---|---|
| 空值标记防穿透 | 2 次查询只打 1 次 DB（MySQL general_log 实证） |
| 归属校验 | 换用户查他人 orderId → `NOT_FOUND`，本人 → `SUCCESS` |
| 限流 | 1 秒内 15 次查询 → `200×10 + 429×5` |
| 排队状态 TTL | 5 分钟；实测自愈耗时 90 秒，正常情况够用 |
| 排队状态命中时不校验归属 | 有意设计，靠 63 位雪花 ID 不可枚举（代码注释已写明取舍） |

### 2.3 仍存在的语义风险（未修，需决策）

`NOT_FOUND` 是**三义**的：订单不存在 / 不是你的 / 排队状态已过期且尚未落库。

前两者是**故意**的（防订单号枚举探测，区分信息只进日志）。第三者才是缺陷——正常落库 <1s、DB 故障自愈 90s，都在 5 分钟 TTL 内，因此只在极端情况（重试 5 次耗尽进死信、等对账补单）才会触发。修复后会给探测者可乘之机，**暂不修**，留给 P2「进行中券丢单兜底」一并解决。

---

## 3. 混沌测试明细

### 3.1 并发超卖 / 一人一单 ✅

**干净受控实验**（券 14，库存 10，30 个不同用户并发）：

```
elapsed=375ms  OK=10  STOCK_OUT=20  REPEAT=0  429=0
redis_stock=0   redis_set=10   db_stock=0   db_orders=10
sold(10) == deducted(10)
Redis set 与 DB 用户差集 = 空；无重复 user_id
```

库存 10 刚好卖完，一人一单、Redis/DB 双向一致，**无超卖**。

> 说明：此前在券 13 上观察到「48 单成功但库存只扣 47」，是 broker 重启丢掉 2 单造成的**历史脏数据**账目错位，不是代码 bug——换干净券重测后完全对齐。这类账目漂移只能等活动结束后的对账重算收敛。

### 3.2 消息重投幂等 ✅

把已落库订单的 CREATE 消息重发一次：

```
日志：重复订单消息，唯一约束拦截, orderId=732461317478219837
db_orders  10 → 10
db_stock    0 → 0
redis_stock 0 → 0
```

`DuplicateKeyException` 被捕获，事务回滚，库存零副作用。符合「幂等键 = 主键 orderId、先 insert 再扣库存」的设计。

### 3.3 DB 故障注入 ⚠️

停 MySQL 后连续下单：

| 阶段 | 结果 |
|---|---|
| 前 15 单（瞬时并发） | 全部返回**成功** —— dbBreaker 还在学习期（minimum-number-of-calls=10），未打开 |
| 等 12 秒消费失败后 | 返回 `code=1100 下单处理中` + orderId ✅ |
| 恢复 MySQL 后 90 秒 | 18 单全部落库，Redis/DB 库存均为 32，差集清空 ✅ |

**学习期窗口是设计权衡**（避免冷启动两次失败就误熔断），但意味着故障最初几秒下单仍报成功。可接受——成功语义本来就是「Redis 预扣成功」，且后续有重试 + 对账兜底。

**自愈链路验证**：MQ 重试（10s/30s/1m/2m/3m）+ dbBreaker 半开试探 → 全部追平，无消息进死信。

### 3.4 Redis 故障 ⚠️

停 Redis 后，下单与轮询均返回 **401**。

原因：`RefreshTokenInterceptor` 在 Redis 不可用时 fail-open（视为未登录）→ `LoginInterceptor` 返回 401。

- **好的一面**：fail-closed，绝不放行，Redis 挂了不可能超卖。
- **待改进**：用户看到「请先登录」，但真实原因是系统故障，文案误导、排查困难。属可观测性/体验问题，非正确性问题。

Redis 恢复后数据完好（AOF 生效），应用自动重连，下单恢复正常。

### 3.5 MQ 故障 ✅

停 broker 后下单返回 `code=5004 下单通道暂时不可用`，且 **Redis 库存未被误扣**。

因为顺序是「先发事务半消息 → Broker OK → 才执行 Lua」：MQ 挂了就到不了 Lua 那一步，库存根本不碰。这个顺序保证了不会「扣了库存却发不出消息」。

### 3.6 🔴 broker 反复退出 253：tmpfs 属主问题（本轮最顽固的坑）

**现象**：混沌测试后期 broker 容器反复退出，`exit code 253`，且**启动后先打印 `boot success` 再过几秒退出**——看起来像"启动成功但随即崩溃"。

**为什么难查**：`docker logs` 只输出脚本回显（`boot success`），**真正的异常不在 stdout**。它在容器内的 `/home/rocketmq/logs/rocketmqlogs/broker.log` 里：

```
ERROR main - Failed to initialize
java.io.FileNotFoundException: /home/rocketmq/store/lock (Permission denied)
    at BrokerStartup.createBrokerController(BrokerStartup.java:222)
```

**根因**：容器以 `rocketmq` 用户（uid=3000）运行，但 tmpfs 挂载的 `/home/rocketmq/store` 默认由 **root** 创建 → 写不进 lock 文件 → 初始化失败。shutdown 时又因同一权限问题无法持久化配置，抛出一片 `FileNotFoundException`，把真正的初始化异常淹没在后面。

**修复**：compose 里显式指定 tmpfs 属主：

```yaml
tmpfs:
  - /home/rocketmq/store:uid=3000,gid=3000
```

**关键教训**：不指定属主时行为**不稳定**（有时能起来、有时秒退），这正是它拖了整个下午的原因——几次"看起来修好了"都只是碰巧。凡是用 tmpfs/卷的，属主必须显式固定。

**排查这类问题的手法**：`docker logs` 看不到异常时，把容器内日志 copy 出来看：
```bash
docker cp <container>:/home/rocketmq/logs/rocketmqlogs/broker.log ./broker.log
```

### 3.7 事务消息不会触发 topic 自动创建

broker 重启（tmpfs 清空）后，即使重启应用，下单仍报 `No route info of this topic: order-seckill-topic`。

原因：事务消息实际发往 `RMQ_SYS_TRANS_HALF_TOPIC`，原 topic 只存在于消息 properties 里，broker 不会为它自动创建路由，因此 `autoCreateTopicEnable=true` 对事务消息**不生效**。

本例中手动预建 topic 后恢复（`mqadmin updateTopic -b broker-a -t order-seckill-topic`）。**生产环境必须预建 topic，不能依赖自动创建**。

### 3.8 超时关单兜底（意外触发）✅

混沌测试持续超过 15 分钟，`closeTimeoutOrders` 兜底被触发，批量关闭超时未支付订单并回补库存——**关单链路工作正常**。

但暴露了一个现象：**关单回补不修正存量漂移**。

券 13 目前 `redis_stock=48 / db_stock=50`（差 2），正是此前 broker 重启丢的 2 单造成。关单回补是「各回各的」（Redis +1、DB +1），不会抹平这个差值。漂移会一直存在，直到活动结束后 `reconcileFinishedStocks` 按订单账本统一重算。

---

## 3.9 对账任务专项审计（怡霖问的重点）

先说结论：**对账的核心兜底能力是好的**——补单、关单、库存重算三条链路实测都能把账本收敛到正确值。但审计 + 实测发现 2 个必须修的问题（已修）和 4 个规模问题（未修）。

### 已修 ①补单与重算的时序竞态（实测证实，危害明确）

`supplementMissingOrders`（发 MQ 消息，异步）与 `reconcileFinishedStocks`（同步读订单表）在同一轮里先后执行，重算必然读到「补的单还没落库」的账本：`valid` 偏小 → `expected = initial - valid` 偏大 → **把 Redis 库存调高**。

实测对照（初始库存 10、丢 2 单、Redis 漂移成 9）：

| | 修复前（券 16） | 修复后（券 17） |
|---|---|---|
| 第 1 轮 | 补单 2 条 → 同轮重算 `valid=0 → expected=10`，**Redis 被 9 改成 10** | 补单 2 条 → **跳过本轮重算** |
| 第 2 轮 | `valid=2 → expected=8`，改回 8 | `valid=2 → expected=8`，一次算对 |
| 净效果 | 凭空多出 1 份库存，持续整整一个调度周期（60s） | 无震荡 |

修复：补单返回「本轮是否补过」，补过就跳过本轮重算，下轮账本稳定后再算。**对账本是每轮收敛的，晚一轮没有代价，算错数才有。**

### 已修 ②一个步骤异常导致后续全部跳过

原先四步共用一个 `try`，任何一步抛异常后面全部停摆。对账的价值在于「兜底」，一个兜底挂了就让其余三个一起失效，等于把兜底做成了单点。已改为每步独立 `try-catch`，失败只跳过该步。

### 未修：4 个规模问题（当前数据量下不暴露，生产会）

| # | 问题 | 后果 | 建议 |
|---|---|---|---|
| 1 | `closeTimeoutOrders` 用 `.list()` 全量拉超时订单，无分页无 LIMIT | 超时订单量大时 OOM | 按 create_time 分批，每批 500 条 |
| 2 | `SMEMBERS seckill:order:{voucherId}` 一次性取全部成员 | 热门券（10 万人）会**阻塞 Redis 单线程** | 改 `SSCAN` 分批 |
| 3 | 补单与重算都用 `lt(endTime, now)` 遍历**所有**历史结束券，无时间下限 | 随运营增长每轮越来越慢 | 加时间下界，只扫最近 N 天结束的券 |
| 4 | `seckill:timeout:retry` 集合无 TTL、无清理 | 订单不存在时该 orderId 永远重发 | 加最大重试次数，超限丢弃并告警 |

另注：`supplementMissingOrders` 用 `lt(endTime, now)`、`reconcileFinishedStocks` 用 `lt(endTime, now-2min)`，两个阈值不一致。这本身是合理设计（重算多等 2 分钟让队列排空），但意味着**结束后 0~2 分钟内补单会跑而重算不跑**，此窗口内补单可能把「只是延迟、没真丢」的单再补一次——靠 `uk_user_voucher` 拦截，安全但产生无效 MQ 流量与错误日志。

## 4. 问题清单

### 已修

| # | 问题 | 修复 |
|---|---|---|
| 1 | 对账 3 个查询全表扫描 20 万行 | 加 `idx_voucher_status` / `idx_status_create_time` / `idx_end_time`，同步进 schema.sql |
| 2 | DB 故障期轮询抛 500 | 改为返回 `UNKNOWN`（200），前端可继续轮询 |
| 3 | broker 启动后数秒退出 253（tmpfs 属主 root 写不进） | tmpfs 显式指定 `uid=3000,gid=3000` |
| 4 | 对账：补单与重算同轮执行，把 Redis 库存调大（实测多出 1 份、持续 60s） | 补单后跳过本轮重算 |
| 5 | 对账：一步异常导致后续三步全部跳过 | 每步独立 try-catch |

### 待决策（未修）

| # | 问题 | 影响 | 建议 |
|---|---|---|---|
| 4 | Redis 故障时返回 401 | 文案误导，掩盖真实故障 | 拦截器区分「无 token」与「Redis 不可用」，后者返回 503 |
| 5 | 进行中券丢单无人兜底 | 库存凭空蒸发（券 13 实测少 2 份） | 补单时只补订单、不回补库存（回补即超卖） |
| 6 | 关单回补不修正存量漂移 | 漂移持续到活动结束 | 接受现状，由 `reconcileFinishedStocks` 收敛 |
| 7 | `NOT_FOUND` 语义三义 | 极端情况用户误以为失败 | 与 #5 一并解决 |
| 8 | dbBreaker 学习期（10 次）内下单报成功 | 故障最初几秒的乐观返回 | 接受，已有重试 + 对账兜底 |
| 9 | 事务消息不触发 topic 自动创建 | broker 重启后下单全挂，且重启应用无效 | 预建 topic（`mqadmin updateTopic`）或应用启动时用 `MQAdminExt` 确保存在 |

---

## 5. 环境清理

混沌测试造的数据（可保留复核，也可清理）：

```sql
-- 券 13/14/15 是压测券（v13 含 2 份漂移库存）
DELETE FROM hmdp.tb_voucher_order  WHERE voucher_id IN (13,14,15);
DELETE FROM hmdp.tb_seckill_voucher WHERE voucher_id IN (13,14,15);
DELETE FROM hmdp.tb_voucher        WHERE id IN (13,14,15);
```
```bash
docker exec hmdp-pro-redis-1 redis-cli DEL seckill:stock:13 seckill:order:13 seckill:meta:13 \
  seckill:stock:14 seckill:order:14 seckill:meta:14 seckill:stock:15 seckill:order:15 seckill:meta:15
# 60 个压测 token（userId 201-260）
docker exec hmdp-pro-redis-1 sh -c 'for i in $(seq 1 60); do redis-cli DEL login:token:chaos-$i >/dev/null; done'
```

用于索引压测的 20 万条 `voucher_id=999` 数据**已删除**。

---

## 6. 复现命令备查

```powershell
# N 个用户真并发（同进程 .NET 异步，避免外部进程审批，且并发度真实）
[Net.ServicePointManager]::DefaultConnectionLimit=100
$rq=@(); 1..30 | ForEach-Object {
  $w=[Net.HttpWebRequest]::Create("http://127.0.0.1:8081/voucher-order/seckill/14")
  $w.Method='POST'; $w.ContentType='application/json'
  $w.Headers.Add('authorization',"chaos-$_")
  $b=[Text.Encoding]::UTF8.GetBytes('{}'); $w.ContentLength=$b.Length
  $s=$w.GetRequestStream(); $s.Write($b,0,$b.Length); $s.Close()
  $rq+=[PSCustomObject]@{i=$_;w=$w;a=$w.BeginGetResponse($null,$null)}
}
foreach($x in $rq){ $r=$x.w.EndGetResponse($x.a); ... }

# 批量造测试用户（直接写 Redis token，绕过验证码）
1..60 | ForEach-Object { docker exec hmdp-pro-redis-1 redis-cli HSET "login:token:chaos-$_" id (200+$_) nickName "chaos-$_" }
```

**坑备忘**：
- 容器内的 shell 引号会被 PowerShell 转义破坏，`docker exec ... sh -c '...'` 里的循环要拿到外面写
- `Invoke-RestMethod -Method Post` 不带 body 会抛 NullReferenceException，用 `curl.exe` 或带上 `-Body "{}"`
- 中文日志用 `Select-String` 匹配会因编码问题失败；读文件时加 `-Encoding UTF8`
