# 测试设计

主缝是 `POST /voucher-order/seckill/{id}` 与 `GET /voucher-order/seckill/result/{orderId}`。HTTP 证明用户可见行为；MySQL 是最终真值；Redis 用于准备活动状态并交叉验证预扣与一人一券；Prometheus reason 仅作辅助证据。

## 20 条 pytest

| 类别 | 覆盖内容 |
|---|---|
| 窗口 W | 开始前、开始前 1 秒、开始后、结束前 1 秒、结束后优先级、券不存在负缓存、活动中库存 key 丢失 fail-closed |
| 并发 C | 库存充足 100 用户全成功、100 抢 50 精确售罄、同用户并发/串行重试、EARLY 对照、异步结果可查询、补单保持原 orderId、结束后按 DB 重算库存 |
| 限流 R | 第 6 次提交被限、提交与查询配额隔离、滑动窗口恢复、broker 下线、Redis 下线与恢复 |
| 基础设施 | 登录验证码冷却残留时登录工厂可自愈 |

完整执行命令显式关闭 rerun：

```powershell
$env:HMDP_DB_PASSWORD = (docker compose exec -T mysql printenv MYSQL_ROOT_PASSWORD).Trim()
pytest autotest/testcases/test_seckill_chain.py -q --reruns 0
```

实测结果：`20 passed, 351 warnings in 161.09s`。

## 四方对账

每张并发券最终核对：

1. DB 领取记录数。
2. DB `COUNT(DISTINCT user_id)`。
3. DB 剩余库存是否精确等于初始库存减领取记录数。
4. Redis stock、order set 与 claim hash 是否与 DB 相符。

1000 token、库存 300 的压力券最终结果为 DB stock=0、领取记录=300、去重用户=300、Redis order=300、claim=300。

## 稳定性手法

- 每个 pytest 用例动态创建独立券，teardown 按领取记录、秒杀券、券、Redis key 的逆依赖顺序清理。
- 手机号池按序分配，登录通过真实发码接口和 Redis 验证码完成，不伪造 token。
- 异步落库和恢复不用猜测式固定 sleep，而用 `wait_until` 等待真实状态。
- 故障用例标记 `serial/isolate/chaos`，避免并行执行时污染共享 Redis、MQ 和保护档位。
- RocketMQ 使用 named volume 保存 commitlog、topic 和消费位点，使 broker 重启测试不丢基础设施状态。

## 未完成的测试口径

- OpenResty 未启动，本轮没有验证网关 1000/s 令牌桶及网关 429。
- 没有执行 1000/1500 线程档；500 线程已出现吞吐平台和明显排队，停止继续制造无信息量负载。
- 没有构造“queue 状态丢失 + DB 同时不可用”的 UNKNOWN 双故障分支。
