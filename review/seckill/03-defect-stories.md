# 缺陷闭环

## RocketMQ named volume 权限导致 broker 退出

症状：将 broker `/home/rocketmq/store` 从 tmpfs 改为 Docker named volume 后，broker 以退出码 253 结束，日志显示创建 `store/lock` 权限不足。

定位：新 named volume 首次挂载为 root 属主，而镜像内 broker 用户 UID 3000 无法写入。问题不是 RocketMQ 不支持持久卷，而是卷初始化权限不匹配。

修复：容器入口以 root 对 store 执行一次 `chown -R rocketmq:rocketmq`，随后用 `runuser` 立即降权启动 broker。创建探针 topic、重启 broker后 topic 仍存在，证明 commitlog/topic/offset 已落到 named volume。最终不再使用 tmpfs。

## 压测活动未预热导致全量“库存不足”

症状：第一次 JMeter 冒烟产生大量 HTTP 200，但 DB 库存仍为 300、领取记录为 0；抽样业务响应为 code=1003 库存不足。

定位：pytest 动态券 fixture 修改窗口后会删除 meta 与 stock，让具体测试自己控制预热；压测准备脚本复用了这段清理，却没有补回活动中必须存在的 `seckill:stock:{voucherId}`。代码按设计 fail-closed，活动开始后拒绝用可能落后的 DB 库存回填。

修复：`loadtest/seckill/prepare.py` 在清理旧状态后显式写入初始 Redis 库存。重新压测后 DB stock=0、记录=300，Redis order/claim 均为 300。

这个缺陷也说明 JMeter 的 HTTP 0% error 不代表业务成功：业务失败同样返回 HTTP 200，必须抽样响应并做数据库对账。

## 500 并发吞吐不升反而延迟上升

症状：线程从 100 提升到 500，吞吐从约 650.6 req/s 变为 629.1 req/s，平均响应从 141ms 上升至 759ms。

定位：吞吐已经进入平台期。500/629≈795ms，与实测平均接近，是排队的典型信号。服务端同时存在 100 并发 `seckillBulkhead`、Tomcat 工作线程上界、同步事务消息发送，以及同机运行 JMeter/JVM/Docker 的资源竞争。

处理：停止继续执行 1000/1500 线程档，不把更高并发带来的排队当成容量。报告明确记录硬件口径和流量入口；后续若做容量优化，需要分机压测并在负载期间采集 CPU、Tomcat busy threads、MQ send latency 和 bulkhead 拒绝增量。

