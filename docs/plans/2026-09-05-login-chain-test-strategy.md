# 登录链路测试策略：边界、安全与会话续期（2026-09-05）

> Wayfinder 票「登录链路测试策略：边界、安全与会话续期测什么」（map：hmdp-pro 三条链路改造规格与测试策略）的决议文档。
> 全部结论已对照源码核实：`UserServiceImpl` / `RefreshTokenInterceptor` / `LoginInterceptor` / `MvcConfig` / `RegexPatterns` / `RegexUtils` / `RedisConstants`。

## 0. 被测链路

```
POST /user/code ──→ Redis login:code:{phone}（TTL 2 分钟，6 位纯数字）
POST /user/login ──→ Lua 原子校验并消费验证码 ──→ 用户不存在则自动注册
                ──→ 生成 UUID token ──→ Redis login:token:{token}（hash，TTL 30 分钟）
每个请求 ──→ RefreshTokenInterceptor（挂 /**，命中即滑动续期）──→ LoginInterceptor（无用户 → 401）
```

**断言主依据 = HTTP 响应 + 直连 Redis/MySQL**（与两级缓存票同一结论，指标不在本链路使用）。

## 1. 源码核实事实（清单的地基）

| # | 事实 | 出处 |
|---|------|------|
| F1 | 验证码 TTL = 2 分钟，6 位纯数字 | `RedisConstants.LOGIN_CODE_TTL`、`UserServiceImpl:56` |
| F2 | 首次发码写入 60 秒冷却 Key；冷却期内重复请求幂等成功且不覆盖当前验证码 | `UserServiceImpl.sendCode` |
| F3 | 验证码由 Lua 原子比较并删除，只能成功使用一次 | `verify_login_code.lua` |
| F4 | 同号连续错码 5 次后锁定 5 分钟 | `verify_login_code.lua` |
| F5 | token TTL = 30 分钟；RefreshTokenInterceptor 对活跃会话滑动续期 | `RedisConstants.LOGIN_USER_TTL`、`RefreshTokenInterceptor` |
| F6 | logout 幂等删除当前 token，旧 token 随即失效 | `UserController`、`UserServiceImpl` |
| F7 | 手机号正则严卡 11 位大陆号段，拒 `+86` | `RegexPatterns.PHONE_REGEX` |
| F8 | 任意合法手机号自动注册 → 无预置用户也能测全链路 | `UserServiceImpl:87-90` |
| F9 | Redis 挂时 RefreshTokenInterceptor fail-open 放行 → LoginInterceptor 401，不会 500 | `RefreshTokenInterceptor:35-41` |
| F10 | `isCodeInvalid` 在 login 链路中是**死代码**：码格式全靠 `equals` 比对 | `UserServiceImpl:76-81` |

## 2. 安全用例的三层停止线

| 层 | 内容 | 处置 |
|----|------|------|
| **A. 既有语义的正向验证** | 缺/伪造/失效 token → 401；Redis 挂 → 401 不 500（fail-open 是已拍板设计，用例验证设计正确） | 进 pytest |
| **B. 安全缺陷闭环** | 同码复用、发码无频控、错码无锁定、长会话和 logout 缺失 | 先由 pytest 稳定复现，再修复并回归 |
| **C. 已落地防护** | 发码幂等冷却、错码锁定、一次性验证码、30 分钟滑动会话、幂等 logout | 23 条登录套件持续验证 |

新增判据：**测缺陷 ≠ 测修复**——防护尚未实现，就不存在「验证修复」的用例。
三判据（确定性 / 零 sleep / 可重复）与两级缓存票完全套用。

## 3. 用例清单（23 条独立用例：P0×5 / P1×17 / slow×1）

> 编号体系：TC-S = 发码侧，TC-L = 登录与会话。前置里「fixture」见 §4。

### 发码侧（POST /user/code）

| 编号 | 前置 | 步骤 | 预期 | 优先级 | 自动化 |
|------|------|------|------|--------|--------|
| TC-S01 | 无 | phone 参数缺失请求 | 400（框架层，非业务 fail） | P1 | pytest |
| TC-S02 | 无 | phone=空串 / 纯字母 | fail「手机号格式错误」 | P1 | pytest |
| TC-S03 | 无 | phone=10 位 / 12 位数字 | fail（长度边界贴 11 切） | P1 | pytest |
| TC-S04 | 无 | phone=`+86` 前缀合法号 | fail（F7，国际区号被拒） | P1 | pytest |
| TC-S05 | fixture 发码 | 60 秒内再次请求发码 | 幂等成功、Redis 中验证码不变，当前码仍可登录 | **P0** | pytest |
| TC-S06 | fixture 发码 | 60 秒内连续请求 4 次 | 全部幂等成功、验证码始终不变、冷却 Key TTL ≤ 60 秒 | P1 | pytest |

### 登录侧（POST /user/login）

| 编号 | 前置 | 步骤 | 预期 | 优先级 | 自动化 |
|------|------|------|------|--------|--------|
| TC-L01 | 无 | phone 缺失 / 空 / 字母 | fail「手机号格式错误」 | P1 | pytest |
| TC-L02 | 无 | phone=10 / 12 位 | fail | P1 | pytest |
| TC-L03 | 无 | phone=+86 前缀 | fail | P1 | pytest |
| TC-L04 | 不发码 | 正确格式直接登录 | fail「验证码错误」 | P1 | pytest |
| TC-L05 | fixture | 码 +1 错值登录 | fail「验证码错误」 | P1 | pytest |
| TC-L06 | fixture | 5 位短码登录 | fail（F10：无格式校验，靠 equals 不等） | P1 | pytest |
| TC-L07 | fixture + 注入 | 取码后 `EXPIRE login:code:{phone} 1`，等过期 | fail（秒级注入，不标 slow） | P1 | pytest |
| TC-L09 | fixture | 合法号 + 正确码登录 | 返回 token；Redis 有 `login:token:{token}` hash；`GET /user/me` 返回该用户 | **P0** | pytest |
| TC-L10 | 未注册号 fixture | 用新号登录 | 200 + 直连 MySQL 断言 `tb_user` 新增一行（F8） | **P0** | pytest |
| TC-L11 | fixture，登录成功 1 次 | 同码第二次登录 | 失败，证明验证码已被原子消费 | P1 | pytest |
| TC-L12 | TC-L09 的 token | 把 TTL 注入为 60 秒 → 请求一次 → 再查 TTL | TTL 回满至约 1800 秒 | P1 | pytest |

### 会话与拦截器层

| 编号 | 前置 | 步骤 | 预期 | 优先级 | 自动化 |
|------|------|------|------|--------|--------|
| TC-L13 | 无 | 无 authorization 头访问受保护接口 | 401 | **P0** | pytest |
| TC-L14 | 无 | 伪造合法格式 token（Redis 无 key） | 401，非 500 | **P0** | pytest |
| TC-L15 | TC-L09 的 token | 直连 `DEL login:token:{token}` 后访问 | 401（状态注入代替 logout，F6） | P1 | pytest |
| TC-L16 | TC-L09 的 token | `authorization: Bearer {token}`（带前缀） | 401（拦截器不剥 Bearer，前端约定裸 token） | P1 | pytest |
| TC-L17 | fixture | 同一 phone 连试 5 次错码，再提交正确码 | 正确码仍被拒绝，锁定 5 分钟 | P1 | pytest |
| TC-L18 | DEBUG SLEEP 注入 Redis 故障 | 带 token 请求受保护接口 | 401 不 500（F9 fail-open 链路） | P1 | pytest，**slow**（约 30s） |

> TC-L08 与 TC-S05 是同一场景的两侧断言，合并为一条用例，不单独编号。

## 4. fixture 与手法（对 pytest 框架结构票的落地约束）

- **`sms_code(phone)` fixture**（方案 A，已拍板升格为登录链路 fixture 规范）：发码 → 直连 Redis `GET login:code:{phone}` 取码 → 顺手断言 `TTL ≈ 120s`。一次发码，fixture 内不重复发（覆盖语义见 TC-S05）。
- **状态注入代替时间等待**（与两级缓存票同一手法族）：
  - 过期：`EXPIRE login:code:{phone} 1`（TC-L07）；
  - 会话失效：`DEL login:token:{token}`（TC-L15）；
  - Redis 故障：DEBUG SLEEP（TC-L18）。
- **直连 MySQL** 断言自动注册（TC-L10）。
- **白盒单测**：`RegexUtilsTest`（JUnit，纯函数无中间件，`mvn.cmd -B test -Dtest=RegexUtilsTest`）——覆盖 `isPhoneInvalid` 边界（空 / 10 / 11 / 12 位 / +86 / 字母）与 `isCodeInvalid`（6 位规则）。**白盒例外第二处**（与 UidGenerator 同性质），与黑盒 TC-S01~04 / L01~03 构成「同一组边界，白盒锁正则、黑盒锁接口」的两层证明。

## 5. 明确不测（4 条，全部有据）

| 不测项 | 理由 |
|--------|------|
| token 自然过期 | TTL 30 分钟仍不适合测试等待；把 TTL 注入为 60 秒验证续期，DEL 注入验证失效 |
| 真跑 10⁶ 次验证码暴破 | TC-L17 直接验证第 5 次触发锁定，无需穷举验证码空间 |
| logout 的内部删除实现 | 通过接口验证旧 token 401 与重复登出幂等，不绑定 Redis 调用细节 |
| 同号并发登录多 token | 行为是「每次登录发新 token、旧 token 不失效」，属会话管理设计缺陷延伸，§7 一句话带过 |

## 6. 规模口径

- **23 条独立用例，不向两级缓存链路的 15 条看齐**——按本链路自己的分支密度定（一个「验证码错误」失败点切 3 条不同前置，失败响应等价 ≠ 用例等价）。
- P0 = 主流程（TC-S05 / L09 / L10 / L13 / L14），P1 = 边界与会话，slow 单独标记（仅 TC-L18）。
- 三条链路密度对齐在抢券票走完后统一收口（map fog 项）。

## 7. 发现与风险（C 层，只记录不改造）

1. **发码幂等冷却已落地**：独立冷却 Key 用 `SET NX EX 60` 抵御重复点击与网络重试；真实部署还应叠加小时级手机号/IP/设备限流。
2. **验证码原子消费已落地**：Lua 把比较与删除合成一个原子动作，堵住并发复用。
3. **错码锁定已落地**：5 次失败后锁定 5 分钟，计数与锁定由同一 Lua 完成。
4. **会话已收敛**：30 分钟滑动 TTL；logout 幂等删除当前 token。
5. **验证码格式校验已接入**：非法格式不进入 Redis 状态机。
6. **敏感日志已清理**：不再输出验证码明文；pytest 通过 Redis fixture 取码。

## 8. 与地图其他票的关系

- **pytest 接口自动化框架的工程结构**（已关闭）：`sms_code` fixture 与状态注入手法是框架层的直接输入。
- **抢券链路测试策略**（进行中）：三判据与三层安全停止线可复用；密度对齐等其关票后统一收口。
