package com.hmdp.observability;

import org.springframework.core.task.TaskDecorator;

import java.util.Map;

/**
 * 线程池边界：把提交方线程的 MDC 快照带进子线程。
 *
 * <p><b>时机是关键</b>：{@link #decorate(Runnable)} 由线程池在 {@code submit()} / {@code execute()}
 * 时刻调用，此时仍运行在「提交方线程」上，所以 {@code snapshot()} 拿到的正是发起请求的那个上下文。
 * 如果改成在子线程里再去读 MDC，只能读到池子里上一个任务的残留或空值 —— ThreadLocal 取不到父线程的值。
 *
 * <p>更特殊的场景（任务执行中途才 set 上下文、{@code new Thread}、ForkJoinPool）需要
 * 阿里 TransmittableThreadLocal，本项目暂无此类用法。
 */
public class MdcTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String, String> snapshot = TraceContext.snapshot();
        return TraceContext.wrap(runnable, snapshot);
    }
}
