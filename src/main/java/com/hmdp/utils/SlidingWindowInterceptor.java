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
 * 业务层滑动窗口限流：领券相关接口按 userId 限流（需在登录拦截器之后）。
 *
 * <p><b>两类请求各算各的配额，互不占用：</b>
 * <table>
 *   <tr><td>POST /voucher-order/seckill/{id}</td><td>领券</td><td>严格（5 次/秒）</td></tr>
 *   <tr><td>GET /voucher-order/seckill/result/{id}</td><td>查落库</td><td>宽松但要封顶</td></tr>
 * </table>
 *
 * <p>为什么要拆开：共用一个 key 的话，前端为了拿到结果多查几次，就把自己的
 * 领券额度烧光了——真正想领券时反而被 429。两者性质不同，配额也该不同。
 *
 * <p><b>限流的真正目的是防穿透，不是防轮询洪峰。</b>
 * 轮询规模被库存数封顶：只有领券成功（或降级拿到 orderId）的人才查得了，
 * 而成功人数 ≤ 库存数，不存在「几十万人同时轮询」。
 * 真正的风险是攻击者伪造 orderId 反复查询，每次都绕开排队状态缓存直落 DB。
 * 所以查询类配额可以宽松（正常轮询 2 次/秒，10 次/秒足够），但绝不能没有。
 */
@Slf4j
@Component
public class SlidingWindowInterceptor implements HandlerInterceptor {

    /** 结果查询（落库）的 URI 片段 */
    private static final String URI_SECKILL_RESULT = "/seckill/result/";

    @Resource
    private SlidingWindowRateLimiter slidingWindowRateLimiter;

    @Value("${seckill.rate-limit.sliding-window.enabled:true}")
    private boolean enabled;

    // ---- 领券动作：严格 ----
    @Value("${seckill.rate-limit.sliding-window.window-ms:1000}")
    private long windowMs;

    @Value("${seckill.rate-limit.sliding-window.max-requests:5}")
    private int maxRequests;

    // ---- 结果查询（落库）：宽松但要封顶 ----
    @Value("${seckill.rate-limit.sliding-window.result-window-ms:1000}")
    private long resultWindowMs;

    @Value("${seckill.rate-limit.sliding-window.result-max-requests:10}")
    private int resultMaxRequests;

    /**
     * 埋点的 mode tag。方案 B 删除后恒为 A，保留这个常量是为了让历史 Grafana 面板
     * 与告警规则不因 tag 消失而断档——不是留着切换用的。
     */
    private static final String SECKILL_MODE = SeckillMode.A;

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
        String uri = request.getRequestURI();

        String key;
        int max;
        long window;
        // 只有「领券」这一个动作计入秒杀请求数。结果查询是领券派生出来的后续轮询，
        // 算进分母会让「被限流比例」失真（看起来限流很轻）。
        // 默认分支按领券处理：将来若挂了新 path 忘了配规则，至少还有严格限流兜着。
        boolean isSeckillSubmit = !uri.contains(URI_SECKILL_RESULT);

        if (uri.contains(URI_SECKILL_RESULT)) {
            key = RateLimitConstants.SLIDING_WINDOW_SECKILL_RESULT_KEY + userId;
            max = resultMaxRequests;
            window = resultWindowMs;
        } else {
            key = RateLimitConstants.SLIDING_WINDOW_SECKILL_KEY + userId;
            max = maxRequests;
            window = windowMs;
            seckillMetrics.seckillRequested(SECKILL_MODE);
        }

        boolean allowed;
        try {
            allowed = slidingWindowRateLimiter.tryAcquire(key, window, max);
        } catch (Exception e) {
            // 限流器 fail-open：限流是保护手段而不是业务依赖，它自己故障不能把主链路一起拖死。
            // 但必须计数 —— 这个指标就是「系统正在裸奔」的告警信号。
            // （业务层仍是 fail-closed：库存不足、重复领取一律拒绝）
            log.error("滑动窗口限流异常，fail-open 放行, userId={}, uri={}", userId, uri, e);
            seckillMetrics.rateLimitFallback("fail_open");
            return true;
        }
        if (allowed) {
            return true;
        }

        if (isSeckillSubmit) {
            // 请求没进入业务方法，没有计时句柄，传 null
            seckillMetrics.finishSeckill(null, SECKILL_MODE, SeckillMetrics.Reason.RATE_LIMITED);
        } else {
            seckillMetrics.rateLimitFallback("rejected");
        }
        log.warn("滑动窗口限流触发, userId={}, uri={}", userId, uri);
        response.setStatus(429);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(JSONUtil.toJsonStr(Result.fail("请求过于频繁，请稍后再试")));
        return false;
    }
}
