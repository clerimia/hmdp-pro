package com.hmdp.utils;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
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
        if (slidingWindowRateLimiter.tryAcquire(key, windowMs, maxRequests)) {
            return true;
        }
        log.warn("滑动窗口限流触发, userId={}, uri={}", userId, request.getRequestURI());
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
        return false;
    }
}
