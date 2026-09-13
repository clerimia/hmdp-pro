# 限时领券链路面试弹药库

本目录只描述当前代码和本机可重复测试已经证明的能力：活动窗口校验、Redis Lua 原子预扣、RocketMQ 事务消息异步落库、数据库事务与唯一约束、结果查询、限流、故障隔离和活动后对账。

## 阅读顺序

1. [全链路原理](./01-seckill-chain.md)
2. [测试设计](./02-test-design.md)
3. [缺陷闭环](./03-defect-stories.md)
4. [面试口述](./04-interview-script.md)
5. [一页速记](./05-cheatsheet.md)
6. [简历描述](./06-resume-copy.md)
7. [JMeter 压力报告](./07-jmeter-pressure-report.md)

## 已验证结果

- 抢券链路 pytest：20/20 通过，显式关闭 rerun，耗时 161.09 秒。
- JMeter 直连应用：100 线程约 650.6 req/s、平均 141ms；500 线程约 629.1 req/s、平均 759ms。
- 300 库存压测券最终 DB 库存 0、领取记录 300、去重用户 300、Redis order/claim 各 300，无超卖。
- RocketMQ broker 使用 Docker named volume 持久化；重启后探针 topic 仍存在。
- 自动化入口：`autotest/testcases/test_seckill_chain.py`；压测入口：`loadtest/seckill/seckill-smoke.jmx`。

## 能力边界

- 本次压力数据来自同机 JMeter、单 JVM、Docker 中间件，只能说明本机拐点。
- 实测绕过了未启动的 OpenResty，不能据此声称验证了网关 1000/s 令牌桶或 429 构成。
- JMeter 的 0% error 仅表示 HTTP 200；库存不足、重复领取等业务失败也返回 HTTP 200，必须结合 Prometheus reason 和 DB 对账解读。
- “无超卖”结论限定在已执行的用例、故障模型和 300 库存压测范围内，不表述为无限条件下的绝对保证。

