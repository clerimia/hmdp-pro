package com.hmdp.observability;

import org.springframework.stereotype.Component;

/**
 * 缓存链路指标。与 {@link SeckillMetrics} 分开：商铺缓存不属于秒杀链路，
 * 各自维护指标口径，将来加命中率、穿透率只动这里。
 */
@Component
public class CacheMetrics {

    public static final String CACHE_REBUILD = "hmdp.cache.rebuild";

    public static final String CACHE_HIT = "hmdp.cache.hit";

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
}
