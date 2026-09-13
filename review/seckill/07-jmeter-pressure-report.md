# 限时领券 JMeter 压力报告

测试日期：2026-09-06。JMeter 5.6.3，单 JVM Spring Boot，Docker MySQL/Redis/RocketMQ；压测机与应用同机。OpenResty 80 端口当时未启动，因此正式数据直连 `127.0.0.1:8081`，不能用于评价网关令牌桶。

## 场景与数据

测试计划：[seckill-smoke.jmx](../../loadtest/seckill/seckill-smoke.jmx)。CSV 提供 1000 个真实登录 token；每档使用独立 300 库存券，并清理用户限流残留。

| 场景 | 线程 | 持续 | 请求数 | 吞吐 | 平均 | P95 | P99 | 最大 | HTTP 错误 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 有效基线 | 100 | 30s | 19,435 | 650.6/s | 141ms | 272ms | 298ms | 371ms | 0 |
| 容量拐点 | 500 | 60s | 37,891 | 629.1/s | 759ms | 1,121ms | 1,257ms | 1,397ms | 0 |

JMeter dashboard 中 `pct1/pct2/pct3` 分别对应 P90/P95/P99；上表使用 P95 与 P99。

## 正确性对账

500 线程档结束并等待异步消费稳定后：

| 账本 | 结果 |
|---|---:|
| 初始库存 | 300 |
| DB 剩余库存 | 0 |
| DB 领取记录 | 300 |
| DB 去重用户 | 300 |
| Redis stock | 0 |
| Redis order SCARD | 300 |
| Redis claim HLEN | 300 |

精确等式 `0 = 300 - 300` 成立，一人一券等式 `300 = 300` 成立。

## 结果解读

100 到 500 线程时吞吐没有增长，仍在约 630~650 req/s，但平均响应由 141ms 上升到 759ms。500/629≈795ms，与观察到的平均响应接近，说明新增并发主要形成排队。当前边界包括 `seckillBulkhead=100`、Tomcat 工作线程、同步 RocketMQ 事务发送，以及同机 JMeter/JVM/Docker 的资源竞争。

HTTP error=0 不能解释为所有请求领取成功。接口对库存不足、重复领取等业务结果使用 HTTP 200；库存只有 300，而 JMeter 循环发送数万次，因此绝大多数响应必然是预期的业务失败。最终成功数应以 DB 记录和 `hmdp_seckill_result_total{reason}` 判断。

## 探索性运行与缺陷排除

第一次冒烟时压测券缺少 Redis stock，代码按 fail-closed 全部返回库存不足，DB 没有新增。这批数据不计入正式结论。准备脚本已经修复为创建活动后写入 Redis 初始库存，正式两档均使用有效库存状态。

## 可复现命令

```powershell
jmeter -n -t loadtest/seckill/seckill-smoke.jmx `
  -Jthreads=100 -Jduration=30 -JvoucherId=<voucherId> `
  -Jcsv=target/loadtest/seckill/tokens.csv `
  -l target/loadtest/seckill/run/results.jtl `
  -e -o target/loadtest/seckill/run/report
```

压测前必须用 `loadtest/seckill/prepare.py` 创建独立券和 token CSV，并确认 `seckill:stock:{voucherId}` 等于初始库存。压测后等待 MQ 消费稳定，再进行 DB/Redis 四方对账和 teardown。

## 报告边界

- 没有经过 OpenResty，未验证网关 429、rate=1000/s、capacity=3000。
- 没有继续跑 1000/1500 线程；500 线程已给出清晰拐点，继续同机加压主要增加排队。
- 原始 dashboard 位于本机 `target/loadtest/seckill/step-100-valid/report` 和 `step-500/report`，目录已被 `.gitignore` 排除。
- 数据用于解释链路和保护策略，不作为生产 SLA 或容量承诺。
