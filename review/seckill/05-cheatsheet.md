# 限时领券速记

| 项目 | 当前契约 |
|---|---|
| 提交接口 | `POST /voucher-order/seckill/{voucherId}` |
| 结果接口 | `GET /voucher-order/seckill/result/{orderId}` |
| 活动窗口 | `[begin, end)` |
| 提交限流 | 每用户 5 次/秒 |
| 查询限流 | 每用户 10 次/秒，独立配额 |
| 入口舱壁 | 100 并发，等待 0 |
| Redis Lua | 库存扣减 + 用户去重 + txn marker |
| Lua 返回 | 0 成功 / 1 售罄 / 2 重复 / -1 异常 |
| MQ | RocketMQ 事务消息，Lua 决定 COMMIT/ROLLBACK |
| 消费池 | core 8 / max 16 / queue 100 |
| DB 事务 | INSERT 领取记录 + stock-1 同生共死 |
| DB 兜底 | orderId 主键幂等 + `uk_user_voucher` |
| 故障语义 | Redis/MQ 不可用均 fail-closed |
| 最终真值 | MySQL |
| 精确对账 | `stock = initial - order_count` 且 `order_count = distinct_user_count` |
| 自动化 | 20/20，无 rerun |
| 本机压力 | 100 线程 650.6/s；500 线程 629.1/s |

红线：不把 Redis 预扣说成最终落库；不把 HTTP 200 等同业务成功；不把单机 QPS 写成生产容量；不把“已测范围内无超卖”夸大成绝对强一致。

