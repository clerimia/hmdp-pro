# 登录链路一页速记

## 五个数字

- 验证码：6 位数字、TTL 2 分钟。
- 发码：冷却 Key TTL 60 秒，重复请求幂等成功且验证码不变。
- 错码：5 次后锁定 5 分钟。
- token：32 位 UUID、TTL 30 分钟滑动续期。
- 自动化：登录 23/23；pytest 总计 50/50；Java 6/6。

## 五个 Redis Key

`login:code:cooldown:{phone}` → `login:code:{phone}` → `login:attempts:{phone}` / `login:locked:{phone}` → `login:token:{token}`

## 三个核心设计

1. Lua 原子完成验证码状态机，消灭 check-then-act 竞态。
2. Redis token 支持多实例共享、TTL、滑动续期和主动登出。
3. RefreshTokenInterceptor 恢复上下文，LoginInterceptor 做授权判断，请求结束清 ThreadLocal。

## 三个测试抓手

1. 状态注入：`EXPIRE 1`、`DEL token`、`CLIENT PAUSE`。
2. 跨存储断言：HTTP + Redis + MySQL。
3. 稳定等待：`wait_until`，不用固定 sleep。

## 三个缺陷故事

1. 验证码可复用 → 红灯用例 → Lua 原子消费。
2. 重复发码/无锁定 → SET NX 冷却 + 五次锁定。
3. 缺参 400 被 `/error` 鉴权覆盖为 401 → 排除 `/error`。

## 一句话收尾

我做的不是“给登录接口补几个断言”，而是把安全状态、会话生命周期、依赖故障和跨存储一致性变成可重复执行的质量证据。
