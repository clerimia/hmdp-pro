package com.hmdp.observability;

import org.springframework.stereotype.Component;

/**
 * 缓存链路指标。与 {@link SeckillMetrics} 分开：商铺缓存不属于领券链路，
 * 各自维护指标口径，将来加命中率、穿透率只动这里。
 */
@Component
public class CacheMetrics {

    public static final String CACHE_REBUILD = "hmdp.cache.rebuild";

    public static final String CACHE_HIT = "hmdp.cache.hit";

    /** 写回侧版本核验：快照落后于 DB，放弃写回（治"成功的脏写"） */
    public static final String CACHE_STALE_SKIP = "hmdp.cache.stale_skip";

    /** 命中层级：L1 Caffeine / L2 Redis / L3 DB */
    public static final String LEVEL_L1 = "l1";

    public static final String LEVEL_L2 = "l2";

    public static final String LEVEL_DB = "db";

    private final ObservabilityRecorder recorder;

    public CacheMetrics(ObservabilityRecorder recorder) {
        this.recorder = recorder;
    }

    /** 异步重建结果：失败会打 error，配告警可以发现「缓存长期不刷新」这类静默故障 */
    public void rebuilt(boolean success) {
        recorder.increment(CACHE_REBUILD, "result", success ? "ok" : "error");
    }

    /** 命中层级分布，用于算多级缓存命中率 */
    public void hit(String level) {
        recorder.increment(CACHE_HIT, "level", level);
    }

    /** 写回侧版本核验失败：快照落后于 DB，放弃写回。每次出现 = 撞上一次"重建期间并发更新" */
    public void staleSkip() {
        recorder.increment(CACHE_STALE_SKIP);
    }
}
