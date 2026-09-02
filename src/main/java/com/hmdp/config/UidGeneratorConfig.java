package com.hmdp.config;

import com.baidu.fsg.uid.impl.CachedUidGenerator;
import com.baidu.fsg.uid.worker.DisposableWorkerIdAssigner;
import com.baidu.fsg.uid.worker.WorkerIdAssigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 百度 UidGenerator 配置（订单号生成器）
 *
 * <p>uid-generator 官方未发布 Maven 中央仓库，源码 vendor 在 com.baidu.fsg.uid 包下，
 * 并做了两处适配：去掉 commons-lang 2.x 依赖；worker 节点分配由 MyBatis 改为 JdbcTemplate。
 *
 * <p>默认采用 CachedUidGenerator（RingBuffer 缓存型）：
 * 启动时预生成 8192 << boostPower = 65536 个 UID 填充环形缓冲区，
 * 取号无锁 CAS，缓冲区剩余量低于 paddingFactor(50%) 时异步补货，
 * 另起 60s 定时补货兜底——适合秒杀高并发取号。
 *
 * <p>UID 位分配（共 63bit，恒为正数）：
 * sign(1) | delta seconds(28) | worker id(22) | sequence(13)
 *
 * <p><b>注意 epoch：</b>官方默认 2016-05-20，28bit 时间戳已在 2024 年耗尽；
 * 本项目将 epoch 调整为 2026-01-01，可用约 8.5 年，接近耗尽时调整 epoch 或增大 time-bits。
 */
@Configuration
public class UidGeneratorConfig {

    @Bean
    public WorkerIdAssigner workerIdAssigner(JdbcTemplate jdbcTemplate) {
        return new DisposableWorkerIdAssigner(jdbcTemplate);
    }

    @Bean
    public CachedUidGenerator cachedUidGenerator(WorkerIdAssigner workerIdAssigner,
                                                 @Value("${uid.time-bits:28}") int timeBits,
                                                 @Value("${uid.worker-bits:22}") int workerBits,
                                                 @Value("${uid.seq-bits:13}") int seqBits,
                                                 @Value("${uid.epoch-str:2026-01-01}") String epochStr,
                                                 @Value("${uid.boost-power:3}") int boostPower,
                                                 @Value("${uid.schedule-interval:60}") long scheduleInterval) {
        CachedUidGenerator generator = new CachedUidGenerator();
        generator.setWorkerIdAssigner(workerIdAssigner);
        generator.setTimeBits(timeBits);
        generator.setWorkerBits(workerBits);
        generator.setSeqBits(seqBits);
        generator.setEpochStr(epochStr);
        generator.setBoostPower(boostPower);
        generator.setScheduleInterval(scheduleInterval);
        return generator;
    }
}
