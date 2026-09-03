package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Redisson 客户端。
 *
 * <p>默认参数对故障极不友好，必须收口：
 * <pre>
 *   命令超时    timeout         默认 3000ms
 *   连接超时    connectTimeout  默认 10000ms ← 最危险，Redis 不可达时每次加锁卡 10s
 *   失败重试    retryAttempts   默认 3 次 × retryInterval 1500ms ≈ 4.5s
 * </pre>
 * 三者叠加，一次加锁最坏能卡 17s。全部按「命令超时 800ms」这条基线对齐，
 * 让 Redisson 与 StringRedisTemplate 的失败速度在同一个量级上。
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private String redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    /** 命令超时：与 spring.redis.timeout 保持同一基线（800ms） */
    @Value("${spring.redis.timeout:800ms}")
    private Duration redisTimeout;

    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();
        String address = "redis://" + redisHost + ":" + redisPort;
        SingleServerConfig single = config.useSingleServer().setAddress(address);
        if (redisPassword != null && !redisPassword.isEmpty()) {
            single.setPassword(redisPassword);
        }
        single.setTimeout((int) redisTimeout.toMillis())
                // 连接不可达时快速失败，而不是等 10s
                .setConnectTimeout(1000)
                // 保留 1 次快速重试：瞬时抖动自愈，持续故障则尽快上抛给熔断/降级
                .setRetryAttempts(2)
                .setRetryInterval(200);
        return Redisson.create(config);
    }
}
