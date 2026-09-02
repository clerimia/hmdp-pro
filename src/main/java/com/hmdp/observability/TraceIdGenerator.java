package com.hmdp.observability;

/**
 * traceId 生成策略（可扩展点①）。
 *
 * <p>默认实现是 {@link UuidTraceIdGenerator}。想换成雪花号 / 带业务前缀 / 从网关透传，
 * 只需在容器里注册一个自己的 {@code TraceIdGenerator} Bean：
 * {@code ObservabilityConfig} 里挂了 {@code @ConditionalOnMissingBean}，自定义 Bean 会顶掉默认实现。
 *
 * <p>为什么默认用 UUID 而不是雪花：traceId 只要求「全局唯一」，不要求趋势递增；
 * 趋势递增是给数据库主键防页分裂用的（本项目的订单号才用 UidGenerator）。
 */
public interface TraceIdGenerator {

    /**
     * @return 一个新的 traceId，仅包含 {@code [A-Za-z0-9._-]}，建议不超过 64 字符
     */
    String generate();
}
