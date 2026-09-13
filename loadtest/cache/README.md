# 商铺读链路 JMeter 压测

测试计划 `shop-read.jmx` 通过属性参数运行，不依赖第三方 JMeter 插件：

```powershell
jmeter -n -t loadtest/cache/shop-read.jmx `
  -Jthreads=100 -Jramp=10 -Jduration=30 `
  -Jpath=/shop/1 `
  -l loadtest/cache/results/multilevel-100.jtl `
  -e -o loadtest/cache/results/multilevel-100-report
```

对照接口：

| 场景 | path | 含义 |
|---|---|---|
| 多级缓存 | `/shop/1` | Caffeine L1 → Redis L2 → MySQL |
| Redis 基线 | `/shop/benchmark/redis/1` | 原版 Redis → MySQL，不含 L1 |
| DB 基线 | `/shop/benchmark/db/1` | 每次直查 MySQL |

正式运行前先请求一次对应接口完成预热。非 GUI 模式用于正式压测，HTML Dashboard 用于压后分析。结果目录不应提交版本库。
