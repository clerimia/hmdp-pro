# 商铺缓存读链路 JMeter 压测报告

测试日期：2026-09-06。JMeter 5.6.3，单 JVM Spring Boot，Docker MySQL/Redis；压测机与应用同机，因此结果用于验证链路和保护策略，不作为生产容量承诺。

## 场景

测试计划：[shop-read.jmx](../../loadtest/cache/shop-read.jmx)。每个请求校验 HTTP 200 且响应体 `success=true`。

| 场景 | endpoint | 线程 | 持续 | 吞吐 | 平均 | P95 | P99 | 错误 |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| 多级缓存 | `/shop/1` | 50 | 30s | 7,640/s | 5.42ms | 18ms | 76ms | 0 |
| 多级缓存 | `/shop/1` | 100 | 30s | 7,189/s | 11.59ms | 36ms | 141ms | 4 |
| 多级缓存 | `/shop/1` | 200（首次） | 30s | 4,654/s | 35.77ms | 100ms | 154ms | 171 |
| Redis 对照 | `/shop/benchmark/redis/1` | 100 | 30s | 3,979/s | 20.97ms | 23ms | 30ms | 0 |
| MySQL 对照 | `/shop/benchmark/db/1` | 100 | 30s | 3,753/s | 22.22ms | 64ms | 107ms | 0 |
| 多级缓存 | `/shop/1` | 200（修复后复测） | 30s | 10,652/s | 15.66ms | 82ms | 131ms | 488 |

## 结果解读

`cacheBulkhead` 配置为 50 个并发、等待 0。100/200 线程下超过 50 个许可的请求会立即返回 503，这是主动隔离，不应计入“成功吞吐”；200 线程复测的 488 个错误全部为 503 舱壁拒绝，没有 HTTP 500。Redis 熔断器始终保持 closed，数据库降级舱壁没有被打穿。

首次 200 线程测试出现 1 个 HTTP 500，日志定位为 Resilience4j 反射调用私有 `queryByIdFallback` 的 `IllegalAccessException`。已将 fallback 改为 `public` 并重新打包；复测确认该缺陷消失。

50 线程是当前配置下的有效性基线：L1 命中为主，0 错误、P99 76ms。Redis/MySQL 对照接口使用独立 key 前缀，避免序列化格式互相污染。多级缓存吞吐高于单层对照，说明热点请求主要被 L1 吸收；P99 尾延迟仍受同机压测、JVM 调度和舱壁拒绝影响。

## 可复现命令

```powershell
jmeter -n -t loadtest/cache/shop-read.jmx `
  -Jthreads=50 -Jramp=10 -Jduration=30 -Jpath=/shop/1 `
  -l result.jtl -e -o report
```

正式结果目录在本机 `loadtest/cache/results/20260906-1358` 和 `20260906-1425`，已加入 `.gitignore`，避免把大型 JTL/HTML 报告提交到仓库。

## Redis 停止—恢复动态演练（50 并发可比口径）

场景：50 个 JMeter 线程持续请求 `/shop/1` 100 秒；先稳定运行约 20 秒，然后停止 Redis 约 35 秒，再启动 Redis，继续观察到测试结束。L1 短 TTL 到期后，故障会真实进入 Redis 超时、重试、熔断和 DB fallback 分支。该并发数与正常热点基线一致，可直接比较。

| 阶段 | 观测 |
|---|---|
| Redis 正常 | 约 60,000 个请求全部 200，L1 命中为主 |
| Redis 停止 | 产生 Redis timeout/error；`redisBreaker` 打开，后续请求快速走 fallback；DB fallback 受 20 并发舱壁保护，超限请求返回 503 |
| Redis 恢复初期 | 熔断器仍处于 OPEN，按配置等待约 10 秒，不能立即把流量全部打回 Redis；半开探测成功后逐步恢复 |
| 恢复稳定 | 熔断器在主动请求探测成功后由 `half_open` 回到 `closed`，Redis/L1 链路恢复；本次 609,450 个请求中 608,648 个成功，错误率 0.13%，错误全部为舱壁拒绝 503，无 500 |

Prometheus 最终观测：主动发送 5 个恢复探测请求后，`redisBreaker` 为 `closed`；本次演练累计 `error=156`、`not_permitted=439,920`、`bulkhead_rejected=4,029`。这些计数是动态演练期间的累计事件，说明熔断确实切断了 Redis 继续访问，但也暴露出 DB 降级舱壁容量偏保守的取舍：优先保护数据库，牺牲部分请求可用性。

动态演练全程（含故障窗口）的 JMeter 指标：平均吞吐 **6,097 QPS**，平均响应 7.94ms，P95 **38ms**，P99 **50ms**，最大响应 965ms。该总体分位数包含快速 503；故障期间真正需要重点观察的是 503 比例、fallback 延迟和恢复时间，不能只看全程平均值。此前 30 并发的 2,548 QPS 仅作为探索性演练，不用于性能横向比较。

本次结果验证的是“故障可隔离、恢复可收敛”，不是 Redis 宕机期间的零错误承诺。生产环境应继续关注 Redis 恢复后的半开成功率、DB fallback 503 比例、熔断持续时间和恢复后的 P99 尾延迟。
