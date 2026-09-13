# 两级缓存链路面试弹药库

本目录只描述当前代码和可重复 pytest 已证明的能力：Caffeine L1、Redis L2、MySQL 回源，以及穿透、击穿、一致性窗口和 Redis 故障降级。

## 阅读顺序

1. [商铺缓存读链路图](./shop-cache-read-chain.html)：先用图讲清五条读路径
2. [全链路原理](./01-cache-chain.md)
3. [测试设计](./02-test-design.md)
4. [缺陷闭环](./03-defect-stories.md)
5. [面试口述](./04-interview-script.md)
6. [一页速记](./05-cheatsheet.md)
7. [简历描述](./06-resume-copy.md)
8. [JMeter 读链路压力报告](./07-jmeter-pressure-report.md)

## 已验证结果

- 缓存链路 pytest：15/15 通过（运行时禁用 rerun）。
- pytest 全量回归：65/65 通过（运行时禁用 rerun）。
- Maven 编译打包：通过。
- 自动化入口：`autotest/testcases/test_cache_chain.py`。

## 能力边界

- L1 没有跨实例广播，远端更新后的旧读由 30 秒 TTL 收敛。
- 空值标记只防同一无效 ID 重复穿透，不防大量不同无效 ID。
- TTL jitter 的测试证明机制存在，不宣称证明了大规模雪崩防护效果。
- Cache-Aside 采用提交后短重试删除，不做 Outbox/广播；删除失败由逻辑 TTL、物理 TTL 和可观测告警收敛。
