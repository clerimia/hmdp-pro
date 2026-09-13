# 缺陷闭环

## `@Retry` 被重载入口绕过

症状：Redis `DEBUG SLEEP 8` 时 HTTP 降级正确、熔断 fallback 有增量，但自定义与原生 Resilience4j retry 指标都为 0。

定位：`ShopServiceImpl` 调用带版本核验的 8 参方法，而 `@Retry` 只标在 6 参重载上。注释声称调用方应走 6 参，但版本核验又要求 8 参；同类内部转调也无法穿过 Spring AOP 代理。

修复：将版本感知入口命名为独立公开方法 `queryWithMultiLevelVersioned` 并直接添加 `@Retry`，调用方经注入的 Spring Bean 调用。回归后 D1 在禁用 rerun 时通过，retry、error fallback 和 not-permitted fallback 均有观测证据。

## 测试方案校准

原策略认为 8 秒卡顿中串行 10 请求足以打开熔断器，但修复后的每个请求包含两次 800ms 尝试，只能积累约 5 个最终失败。改为 10 请求并发构造最小样本，再追加一次请求观测 open 快速拒绝，使测试与真实切面边界一致。

## 逻辑过期值污染 L1

原实现发现 L2 逻辑过期后，一边异步重建 Redis，一边把旧值写入 30 秒 L1。结果是 Redis 很快恢复新值，但本实例仍被旧 L1 挡住；删除商铺时还可能出现重建线程清 L1 后请求线程再次写回幽灵旧值。修复后旧值只服务当前请求，并使用 JVM `rebuildingKeys` 在提交线程池前去重；Redisson 锁继续负责跨实例去重。
