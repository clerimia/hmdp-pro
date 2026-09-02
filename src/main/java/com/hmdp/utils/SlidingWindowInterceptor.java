package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.observability.SeckillMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;

/**
 * 业务层滑动窗口限流：秒杀接口按 userId 限流（需在登录拦截器之后）
 */
@Slf4j
@Component
public class SlidingWindowInterceptor implements HandlerInterceptor {

    @Resource
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @Value("${seckill.rate-limit.sliding-window.enabled:true}")
    private boolean enabled;

    @Value("${seckill.rate-limit.sliding-window.window-ms:1000}")
    private long windowMs;

    @Value("${seckill.rate-limit.sliding-window.max-requests:5}")
    private int maxRequests;

    /** 埋点用：区分 A/B 方案的指标 */
    @Value("${seckill.mode:A}")
    private String seckillMode;

    @Resource
    private SeckillMetrics seckillMetrics;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!enabled) {
            return true;
        }
        if (UserHolder.getUser() == null) {
            return true; // 交给登录拦截器处理
        }
        Long userId = UserHolder.getUser().getId();
        String key = RateLimitConstants.SLIDING_WINDOW_SECKILL_KEY + userId;

        // 在限流之前计数，才能算出「被限流比例」= rate_limited / request
        seckillMetrics.seckillRequested(seckillMode);

        boolean allowed;
        try {
            allowed = slidingWindowRateLimiter.tryAcquire(key, windowMs, maxRequests);
        } catch (Exception e) {
            // 限流器 fail-open：限流是保护手段而不是业务依赖，它自己故障不能把主链路一起拖死。
            // 但必须计数 —— 这个指标就是「系统正在裸奔」的告警信号。
            // （业务层仍是 fail-closed：库存不足、重复下单一律拒绝）
            log.error("滑动窗口限流异常，fail-open 放行, userId={}, uri={}", userId, request.getRequestURI(), e);
            seckillMetrics.rateLimitFallback("fail_open");
            return true;
        }
        if (allowed) {
            return true;
        }
        // 请求没进入业务方法，没有计时句柄，传 null
        seckillMetrics.finishSeckill(null, seckillMode, SeckillMetrics.Reason.RATE_LIMITED);
        log.warn("滑动窗口限流触发, userId={}, uri={}", userId, request.getRequestURI());
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
        return false;
    }
}
