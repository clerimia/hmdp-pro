package com.hmdp.observability;

import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * HTTP 入口边界：traceId 生命周期的起点。
 *
 * <p><b>为什么用 Filter 而不是 HandlerInterceptor</b>：Filter 在过滤器链最前端，
 * 404、静态资源、拦截器抛异常之前的日志也都能带上 traceId；Interceptor 只能覆盖进入 DispatcherServlet 的请求。
 *
 * <p><b>串联原理</b>：上游（网关 / 其他服务）传了 {@code X-Trace-Id} 就复用，没有才生成。
 * 与 {@link MqTraceCarrier} 的入口侧逻辑是同一个约定 —— 「有就复用、没有才新建」，
 * 这样链路才能跨进程串起来。
 *
 * <p>响应头回写 traceId：前端报障时可以直接把 id 贴给后端，grep 一次拿到全链路日志。
 */
public class TraceIdFilter extends OncePerRequestFilter {

    private final String headerName;

    private final TraceIdGenerator generator;

    public TraceIdFilter(String headerName, TraceIdGenerator generator) {
        this.headerName = headerName;
        this.generator = generator;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(headerName);
        if (traceId == null || traceId.trim().isEmpty()) {
            traceId = generator.generate();
        }
        // open 返回的是清洗后的值：上游 header 可能含换行等字符，回写响应头必须用清洗结果
        String effective = TraceContext.open(traceId);
        if (effective != null) {
            response.setHeader(headerName, effective);
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 工作线程是池化复用的，不清理会导致下一个请求继承上一个请求的 traceId
            TraceContext.clear();
        }
    }
}
