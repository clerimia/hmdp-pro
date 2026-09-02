package com.hmdp.observability;

import java.util.UUID;

/**
 * 默认 traceId 生成策略：32 位无横线 UUID。
 *
 * <p>32 位而非 36 位的原因：横线在日志与请求头里都是噪声，去掉后长度更稳定、复制检索也方便。
 */
public class UuidTraceIdGenerator implements TraceIdGenerator {

    @Override
    public String generate() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
