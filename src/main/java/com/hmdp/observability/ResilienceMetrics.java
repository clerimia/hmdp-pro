package com.hmdp.observability;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 韧性事件指标（P3 / 设计文档第 10 节）：订阅 Resilience4j 事件流，让「降级动作」在
 * Prometheus 里可见——否则用户投诉时无法判断系统当时是否正在降级。
 *
 * <p>订阅四类事件（fallback 是注解切面机制、没有原生事件，由各 fallbackMethod 手动打点，
 * 见 {@link #fallback}）：
 * <ul>
 *   <li>{@code onError} —— 熔断学习窗口内的真实依赖失败（kind=error）</li>
 *   <li>{@code onCallNotPermitted} —— 熔断打开后被打回的调用（kind=not_permitted），
 *       每分钟增量就是「降级拒流量」</li>
 *   <li>{@code onStateTransition} —— 熔断翻转 closed/open/half_open（state tag），
 *       状态存续由 resilience4j-micrometer 的 state Gauge 负责，这里补「翻转时刻」</li>
 *   <li>{@code onRetry} —— 重试管触发（retry 事件流独立计数）</li>
 * </ul>
 *
 * <p><b>tag 红线</b>（沿用 {@link SeckillMetrics} 同一条）：只用 {@code breaker} / {@code kind} /
 * {@code state} / {@code result} 这类有限枚举，orderId / userId / traceId 一律不做 tag。
 *
 * <p><b>懒注册</b>：实例由 resilience4j-spring-boot2 在首次经过注解切面时才创建进 Registry，
 * 所以除了补订启动期已存在的实例，还要挂 {@code onEntryAdded} 兜住后创建的；
 * 用名字去重防止两条路径重复订阅（重复订阅 = 一次事件计两次）。
 */
@Slf4j
@Component
public class ResilienceMetrics implements InitializingBean {

    /** 熔断调用事件：error / not_permitted（Prometheus: hmdp_resilience_breaker_event_total） */
    public static final String BREAKER_EVENT = "hmdp.resilience.breaker.event";

    /** 熔断状态翻转（Prometheus: hmdp_resilience_breaker_transition_total） */
    public static final String BREAKER_TRANSITION = "hmdp.resilience.breaker.transition";

    /** 重试事件：retry / error（Prometheus: hmdp_resilience_retry_total） */
    public static final String RETRY_EVENT = "hmdp.resilience.retry";

    /** fallback 打点（Prometheus: hmdp_resilience_fallback_total） */
    public static final String FALLBACK = "hmdp.resilience.fallback";

    // ---- kind / state 合法取值，封闭在此（tag 红线） ----
    public static final String KIND_ERROR = "error";
    public static final String KIND_NOT_PERMITTED = "not_permitted";
    public static final String KIND_BULKHEAD_REJECTED = "bulkhead_rejected";
    public static final String KIND_RETRY = "retry";

    @Resource
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @Resource
    private RetryRegistry retryRegistry;

    @Resource
    private ObservabilityRecorder recorder;

    private final Set<String> subscribedBreakers = ConcurrentHashMap.newKeySet();

    private final Set<String> subscribedRetries = ConcurrentHashMap.newKeySet();

    @Override
    public void afterPropertiesSet() {
        circuitBreakerRegistry.getEventPublisher()
                .onEntryAdded(entry -> subscribeBreaker(entry.getAddedEntry()));
        retryRegistry.getEventPublisher()
                .onEntryAdded(entry -> subscribeRetry(entry.getAddedEntry()));
        // 启动期已存在的实例（编程式创建的场景）补订阅
        circuitBreakerRegistry.getAllCircuitBreakers().forEach(this::subscribeBreaker);
        retryRegistry.getAllRetries().forEach(this::subscribeRetry);
        log.info("ResilienceMetrics 已挂载：熔断器 {} 个、重试器 {} 个",
                subscribedBreakers.size(), subscribedRetries.size());
    }

    /**
     * fallback 打点（设计文档「R4J fallback 硬规则 4」）：fallback 必须打点，否则降级是隐形的。
     *
     * @param breaker 触发降级的韧性实例名（redisBreaker / mqBreaker / dbBreaker）
     * @param kind    降级触发原因，取 {@code KIND_NOT_PERMITTED}（熔断打开快速失败）/
     *                {@code KIND_ERROR}（学习期真实失败落入 fallback）/
     *                {@code KIND_BULKHEAD_REJECTED}（舱壁满被打回，降级未执行）
     */
    public void fallback(String breaker, String kind) {
        recorder.increment(FALLBACK, "breaker", breaker, "kind", kind);
    }

    private void subscribeBreaker(CircuitBreaker cb) {
        if (!subscribedBreakers.add(cb.getName())) {
            return;
        }
        String breaker = cb.getName();
        cb.getEventPublisher()
                .onError(e -> recorder.increment(BREAKER_EVENT, "breaker", breaker, "kind", KIND_ERROR))
                .onCallNotPermitted(e -> recorder.increment(BREAKER_EVENT,
                        "breaker", breaker, "kind", KIND_NOT_PERMITTED))
                // toState 用小写对齐 resilience4j-micrometer 的 state tag 口径（closed/open/half_open）
                .onStateTransition(e -> recorder.increment(BREAKER_TRANSITION,
                        "breaker", breaker,
                        "state", e.getStateTransition().getToState().name().toLowerCase()));
    }

    private void subscribeRetry(Retry retry) {
        if (!subscribedRetries.add(retry.getName())) {
            return;
        }
        String name = retry.getName();
        retry.getEventPublisher()
                .onRetry(e -> recorder.increment(RETRY_EVENT, "retry", name, "kind", KIND_RETRY))
                .onError(e -> recorder.increment(RETRY_EVENT, "retry", name, "kind", KIND_ERROR));
    }
}
