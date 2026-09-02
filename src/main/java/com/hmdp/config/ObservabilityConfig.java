package com.hmdp.config;

import com.hmdp.observability.MdcTaskDecorator;
import com.hmdp.observability.TraceContext;
import com.hmdp.observability.TraceIdFilter;
import com.hmdp.observability.TraceIdGenerator;
import com.hmdp.observability.UuidTraceIdGenerator;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 可观测性装配。
 *
 * <p>三个扩展点的接线都在这里，替换实现不用动业务代码：
 * <ol>
 *   <li>traceId 生成策略：注册自己的 {@link TraceIdGenerator} Bean 即可顶掉默认 UUID 实现</li>
 *   <li>跨进程载体：{@link com.hmdp.observability.MqTraceCarrier}（HTTP 侧是 {@link TraceIdFilter} 的 header）</li>
 *   <li>埋点后端：注册自己的 {@link com.hmdp.observability.ObservabilityRecorder} 实现（当前 Micrometer / NoOp 二选一）</li>
 * </ol>
 *
 * <p>放在 {@code config} 包与项目其他 {@code @Configuration} 保持一致；
 * {@code observability} 包本身只放能力类（上下文、埋点门面、边界适配器），不含装配。
 */
@Configuration
public class ObservabilityConfig {

    public static final String TRACE_AWARE_EXECUTOR = "traceAwareExecutor";

    @Value("${hmdp.observability.trace.header-name:X-Trace-Id}")
    private String headerName;

    @Value("${hmdp.observability.trace.enabled:true}")
    private boolean traceEnabled;

    /** 默认 UUID 实现；容器里有自定义 {@link TraceIdGenerator} 时自动让位 */
    @Bean
    @ConditionalOnMissingBean(TraceIdGenerator.class)
    public TraceIdGenerator traceIdGenerator() {
        return new UuidTraceIdGenerator();
    }

    /**
     * 把容器里的生成策略同步给 {@link TraceContext} 的静态持有者：
     * RocketMQ 内部线程、静态工具类等非 Spring 管理的调用方也能拿到同一策略。
     *
     * <p>刻意做成独立 Bean 而不是在配置类里注入字段 —— 避免配置类自己依赖
     * 自己声明的 Bean 造成自注入循环。
     */
    @Bean
    public InitializingBean traceContextInitializer(TraceIdGenerator generator) {
        return () -> TraceContext.setGenerator(generator::generate);
    }

    /**
     * 入口 Filter 必须是过滤器链第一个：后续所有组件（含拦截器、异常处理）的日志都要带 traceId。
     */
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter(TraceIdGenerator generator) {
        FilterRegistrationBean<TraceIdFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new TraceIdFilter(headerName, generator));
        registration.addUrlPatterns("/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setEnabled(traceEnabled);
        return registration;
    }

    /**
     * 带 MDC 传播的线程池：所有「提交任务 → 子线程执行」的场景统一用它，
     * 子线程日志才会带上发起请求的 traceId（依赖 {@link MdcTaskDecorator}）。
     *
     * <p>拒绝策略用 CallerRuns：缓存重建这类任务宁可让提交方线程慢一点，
     * 也不要静默丢弃（丢弃后缓存永远不重建，属于业务可见故障）。
     */
    @Bean(TRACE_AWARE_EXECUTOR)
    public ThreadPoolTaskExecutor traceAwareExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(2000);
        executor.setThreadNamePrefix("trace-aware-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
