package com.hmdp.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存（读写链路 Java 侧 L1）
 */
@Configuration
public class CaffeineConfig {

    /**
     * 商铺本地缓存：最大 10000 条，写入后 30 秒过期
     *
     * <p>TTL 取短是有意的：本地缓存无法跨实例主动失效（驱逐通知只会命中
     * 发起写操作的那个实例），跨实例脏数据只能靠 TTL 自愈——30s 把多实例
     * 一致性窗口从 10min 压到 30s，代价是命中率下降、Redis 读 QPS 上升。
     *
     * <p><b>值是共享可变引用</b>：缓存里存的是对象实例本身（不做拷贝），同 key 的
     * 所有请求拿到同一实例。调用方不得修改缓存返回的对象——in-place 修改会直接
     * 污染 L1（并连带后续写回 L2 的值），见 MultiLevelCacheService 的不可变约定。
     */
    @Bean
    public Cache<String, Object> shopLocalCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }
}
