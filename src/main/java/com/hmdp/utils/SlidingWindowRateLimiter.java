package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.UUID;

/**
 * 业务层滑动窗口限流（Redis ZSET）
 */
@Slf4j
@Component
public class SlidingWindowRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("sliding_window.lua"));
        SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * @param key        Redis key
     * @param windowMs   窗口长度（毫秒）
     * @param maxRequests 窗口内最大请求数
     * @return true 放行；false 被限流
     */
    public boolean tryAcquire(String key, long windowMs, int maxRequests) {
        long now = System.currentTimeMillis();
        String member = now + "-" + UUID.randomUUID().toString().substring(0, 8);
        Long r = stringRedisTemplate.execute(
                SCRIPT,
                Collections.singletonList(key),
                String.valueOf(now),
                String.valueOf(windowMs),
                String.valueOf(maxRequests),
                member
        );
        return r != null && r == 1L;
    }
}
