package com.hmdp.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 空埋点实现：{@code hmdp.observability.metrics.enabled=false} 时生效（压测用）。
 *
 * <p>配合 {@link MicrometerRecorder} 的条件装配，业务代码永远拿得到一个非空实现，
 * 因此埋点调用点不需要做任何判空 —— 这也是埋点门面存在的价值之一。
 */
@Component
@ConditionalOnProperty(prefix = "hmdp.observability.metrics", name = "enabled", havingValue = "false")
public class NoOpRecorder implements ObservabilityRecorder {

    @Override
    public void increment(String metric, String... tags) {
        // no-op
    }

    @Override
    public Sample startTimer() {
        return new Sample() {
        };
    }

    @Override
    public void stopTimer(Sample sample, String metric, String... tags) {
        // no-op
    }
}
