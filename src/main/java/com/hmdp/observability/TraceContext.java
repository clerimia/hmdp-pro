package com.hmdp.observability;

import org.slf4j.MDC;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

/**
 * 链路上下文门面：所有「边界」统一走这里读写 MDC。
 *
 * <p><b>为什么需要它</b>：MDC 底层是 ThreadLocal，跨线程（线程池）和跨进程（MQ）都会断。
 * 项目里有四类边界，全部复用本类的同一套动作：
 * <pre>
 *   HTTP 入口   TraceIdFilter         → open(上游 header 或新生成) → finally clear()
 *   线程池      MdcTaskDecorator      → snapshot()（submit 时刻）→ wrap() → finally clear()
 *   MQ 发送     MqTraceCarrier#inject → current()
 *   MQ 消费     OrderMQConsumer       → open(MqTraceCarrier#extract) → finally clear()
 * </pre>
 *
 * <p><b>可扩展点</b>：将来换 OpenTelemetry / SkyWalking 的 Context，只需改本类内部实现，
 * 四个边界的调用方一行都不用动。
 *
 * <p><b>安全</b>：{@link #open(String)} 会清洗输入。traceId 可能来自外部请求头，
 * 不清洗会出现日志注入（伪造日志行）和超长值撑爆日志字段。
 */
public final class TraceContext {

    /** MDC 中的 key，对应日志 pattern 的 {@code %X{traceId}} */
    public static final String TRACE_ID_KEY = "traceId";

    /** traceId 白名单字符：UUID（去横线）+ 重试后缀 -r{n}，其余一律剔除 */
    private static final String SAFE_PATTERN = "[^A-Za-z0-9._-]";

    private static final int MAX_LENGTH = 64;

    private static final Supplier<String> DEFAULT_GENERATOR =
            () -> UUID.randomUUID().toString().replace("-", "");

    /**
     * 生成策略持有者。默认 UUID，由 {@code ObservabilityConfig} 注入 Spring 容器里的
     * {@link TraceIdGenerator} Bean 覆盖；非 Spring 管理的线程（RocketMQ 内部线程池等）
     * 走这条兜底路径，保证任何地方都能拿到一致策略。
     */
    private static volatile Supplier<String> generator = DEFAULT_GENERATOR;

    private TraceContext() {
    }

    public static void setGenerator(Supplier<String> custom) {
        if (custom != null) {
            generator = custom;
        }
    }

    public static String newTraceId() {
        return generator.get();
    }

    /** 当前线程的 traceId，未开启链路时为 null */
    public static String current() {
        return MDC.get(TRACE_ID_KEY);
    }

    /**
     * 打开一段上下文。传 null / 空白不会覆盖已有值（例如内部生成失败时要保留上游 traceId）。
     *
     * @return 实际写入 MDC 的（已清洗的）traceId；入参非法时返回 null
     */
    public static String open(String traceId) {
        String safe = sanitize(traceId);
        if (safe != null) {
            MDC.put(TRACE_ID_KEY, safe);
        }
        return safe;
    }

    /** 快照当前上下文，供跨线程传递。必须在「提交方线程」调用 */
    public static Map<String, String> snapshot() {
        Map<String, String> ctx = MDC.getCopyOfContextMap();
        return ctx == null ? Collections.<String, String>emptyMap() : ctx;
    }

    /**
     * 还原快照。空快照会整体覆盖 MDC（等价于清理），
     * 避免线程池复用时把上一个任务的上下文带给下一个任务。
     */
    public static void restore(Map<String, String> ctx) {
        if (ctx != null) {
            MDC.setContextMap(ctx);
        }
    }

    /**
     * 清理当前线程上下文。
     * <b>必须放在 finally</b>：Tomcat 工作线程、MQ 消费线程、缓存重建线程全部是池化复用的，
     * 不清理会让下一个请求打出上一个请求的 traceId（日志串号，比没有 traceId 更难排查）。
     */
    public static void clear() {
        MDC.clear();
    }

    /** 包装任务：执行前还原快照，执行后清理。快照由调用方在提交时刻捕获 */
    public static Runnable wrap(Runnable task, Map<String, String> ctx) {
        return () -> {
            restore(ctx);
            try {
                task.run();
            } finally {
                clear();
            }
        };
    }

    /** 同上，Callable 版本 */
    public static <T> Callable<T> wrap(Callable<T> task, Map<String, String> ctx) {
        return () -> {
            restore(ctx);
            try {
                return task.call();
            } finally {
                clear();
            }
        };
    }

    /** 清洗：去首尾空白 → 截断 → 剔除白名单外字符（含换行等控制字符） */
    static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            trimmed = trimmed.substring(0, MAX_LENGTH);
        }
        String safe = trimmed.replaceAll(SAFE_PATTERN, "");
        return safe.isEmpty() ? null : safe;
    }
}
