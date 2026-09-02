package com.hmdp.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 默认埋点实现：Micrometer → Prometheus。
 *
 * <p>指标按 (名称 + tags) 缓存：注册本质是 registry 内部的一次 map 查找，
 * 但在秒杀这种热路径上，自己缓存一层可以把开销压到最低。
 *
 * <p>tag 值在这里做统一兜底：null / 空串一律替换为 {@code unknown}，
 * 奇数个 tag 直接抛异常（宁可启动期就炸，也不要静默产生畸形指标）。
 */
@Component
@ConditionalOnProperty(prefix = "hmdp.observability.metrics", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MicrometerRecorder implements ObservabilityRecorder {

    private final MeterRegistry registry;

    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    private final Map<String, Timer> timers = new ConcurrentHashMap<>();

    public MicrometerRecorder(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void increment(String metric, String... tags) {
        String[] normalized = normalize(tags);
        String key = key(metric, normalized);
        counters.computeIfAbsent(key,
                k -> Counter.builder(metric).tags(normalized).register(registry)).increment();
    }

    @Override
    public Sample startTimer() {
        return new MicrometerSample(Timer.start(registry));
    }

    @Override
    public void stopTimer(Sample sample, String metric, String... tags) {
        if (!(sample instanceof MicrometerSample)) {
            return;
        }
        String[] normalized = normalize(tags);
        String key = key(metric, normalized);
        Timer timer = timers.computeIfAbsent(key,
                k -> Timer.builder(metric).tags(normalized).register(registry));
        ((MicrometerSample) sample).delegate.stop(timer);
    }

    static String[] normalize(String... tags) {
        if (tags == null || tags.length == 0) {
            return new String[0];
        }
        if (tags.length % 2 != 0) {
            throw new IllegalArgumentException("tags 必须成对出现: " + Arrays.toString(tags));
        }
        String[] copy = Arrays.copyOf(tags, tags.length);
        for (int i = 1; i < copy.length; i += 2) {
            if (copy[i] == null || copy[i].isEmpty()) {
                copy[i] = "unknown";
            }
        }
        return copy;
    }

    private static String key(String metric, String[] normalizedTags) {
        return metric + "|" + Arrays.toString(normalizedTags);
    }

    private static final class MicrometerSample implements Sample {

        private final Timer.Sample delegate;

        private MicrometerSample(Timer.Sample delegate) {
            this.delegate = delegate;
        }
    }
}
