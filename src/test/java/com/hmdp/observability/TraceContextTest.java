package com.hmdp.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 MDC 上下文的跨线程传递与清理。
 *
 * <p>这几条断言对应的正是生产上最容易出事的两个点：
 * 子线程拿不到上下文（链路断开）、线程复用导致 traceId 串号（比没有 traceId 更难排查）。
 */
class TraceContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void 子线程能读到提交方的traceId() throws Exception {
        TraceContext.open("parent-1");
        // 快照必须在提交方线程捕获
        Map<String, String> snapshot = TraceContext.snapshot();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> seen = new AtomicReference<>();
        try {
            pool.submit(TraceContext.wrap(() -> seen.set(TraceContext.current()), snapshot)).get();
        } finally {
            pool.shutdown();
        }
        assertEquals("parent-1", seen.get());
    }

    @Test
    void 任务执行后清理_线程复用不串号() throws Exception {
        TraceContext.open("req-a");
        Map<String, String> firstSnapshot = TraceContext.snapshot();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        AtomicReference<String> firstSeen = new AtomicReference<>("未执行");
        AtomicReference<String> secondSeen = new AtomicReference<>("未执行");
        try {
            pool.submit(TraceContext.wrap(() -> firstSeen.set(TraceContext.current()), firstSnapshot)).get();

            // 提交方线程清空后（等价于下一个请求未携带上下文）再提交一个任务
            TraceContext.clear();
            Map<String, String> secondSnapshot = TraceContext.snapshot();
            pool.submit(TraceContext.wrap(() -> secondSeen.set(TraceContext.current()), secondSnapshot)).get();
        } finally {
            pool.shutdown();
        }

        assertEquals("req-a", firstSeen.get());
        // 同一个工作线程：如果上一个任务没有 clear，这里会读到残留的 req-a
        assertNull(secondSeen.get(), "线程复用时不能读到上一个任务的 traceId");
    }

    @Test
    void 外部传入的traceId会被清洗_防日志注入() {
        String effective = TraceContext.open("abc\r\n<script>alert(1)</script>");
        assertEquals("abcscriptalert1script", effective);
        assertEquals("abcscriptalert1script", TraceContext.current());
    }

    @Test
    void 超长traceId被截断到64字符() {
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            raw.append('a');
        }
        assertEquals(64, TraceContext.open(raw.toString()).length());
    }

    @Test
    void 空值不覆盖已有上下文() {
        TraceContext.open("keep-me");
        assertNull(TraceContext.open("   "), "空白输入不应写入 MDC");
        assertEquals("keep-me", TraceContext.current());
    }

    @Test
    void 生成器可替换() {
        TraceContext.setGenerator(() -> "fixed-trace");
        try {
            assertEquals("fixed-trace", TraceContext.newTraceId());
        } finally {
            TraceContext.setGenerator(new UuidTraceIdGenerator()::generate);
        }
        // 还原后重新生成的 id 应符合 UUID（去横线 32 位）形态
        String generated = TraceContext.newTraceId();
        assertEquals(32, generated.length());
        assertTrue(generated.matches("[0-9a-f]{32}"), "默认实现应产出 32 位小写十六进制");
    }
}
