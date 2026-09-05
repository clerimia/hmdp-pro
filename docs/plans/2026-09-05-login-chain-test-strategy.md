# 登录链路测试策略：边界、安全与会话续期（2026-09-05）

> Wayfinder 票「登录链路测试策略：边界、安全与会话续期测什么」（map：hmdp-pro 三条链路改造规格与测试策略）的决议文档。
> 全部结论已对照源码核实：`UserServiceImpl` / `RefreshTokenInterceptor` / `LoginInterceptor` / `MvcConfig` / `RegexPatterns` / `RegexUtils` / `RedisConstants`。

## 0. 被测链路

```
POST /user/code ──→ Redis login:code:{phone}（TTL 2 分钟，6 位纯数字）
POST /user/login ──→ 校验码（equals 比对，不删码）──→ 用户不存在则自动注册
                ──→ 生成 UUID token ──→ Redis login:token:{token}（hash，TTL 36000 分钟 = 25 天）
每个请求 ──→ RefreshTokenInterceptor（挂 /**，命中即滑动续期）──→ LoginInterceptor（无用户 → 401）
```

**断言主依据 = HTTP 响应 + 直连 Redis/MySQL**（与两级缓存票同一结论，指标不在本链路使用）。

## 1. 源码核实事实（清单的地基）

| # | 事实 | 出处 |
|---|------|------|
| F1 | 验证码 TTL = 2 分钟，6 位纯数字 | `RedisConstants.LOGIN_CODE_TTL`、`UserServiceImpl:56` |
| F2 | 发码无任何频控，每次覆盖旧码 | `UserServiceImpl.sendCode` |
| F3 | 验证码不是一次性的：登录成功后不删码 | `UserServiceImpl:76-81` |
| F4 | login 无限流（滑动窗口只挂 `/voucher-order/seckill` 两 path） | `MvcConfig:41-44` |
| F5 | token TTL = 36000 分钟 = 25 天；RefreshTokenInterceptor 挂 `/**`，任意请求滑动续期 → 登录态实际永不过期 | `RedisConstants.LOGIN_USER_TTL`、`RefreshTokenInterceptor:51` |
| F6 | logout 是 TODO，返回 `功能未完成` | `UserController:60-64` |
| F7 | 手机号正则严卡 11 位大陆号段，拒 `+86` | `RegexPatterns.PHONE_REGEX` |
| F8 | 任意合法手机号自动注册 → 无预置用户也能测全链路 | `UserServiceImpl:87-90` |
| F9 | Redis 挂时 RefreshTokenInterceptor fail-open 放行 → LoginInterceptor 401，不会 500 | `RefreshTokenInterceptor:35-41` |
| F10 | `isCodeInvalid` 在 login 链路中是**死代码**：码格式全靠 `equals` 比对 | `UserServiceImpl:76-81` |

## 2. 安全用例的三层停止线

| 层 | 内容 | 处置 |
|----|------|------|
| **A. 既有语义的正向验证** | 缺/伪造/失效 token → 401；Redis 挂 → 401 不 500（fail-open 是已拍板设计，用例验证设计正确） | 进 pytest |
| **B. 已存在缺陷的证据用例** | 同码二次登录（F3）、发码无频控（F2）、连错无锁定（F4+F1：6 位数字码在线暴破可行） | 进 pytest——**测试职责是证明缺陷存在，不是修复它** |
| **C. 需产品决策的防护改造** | 发码限流、错码锁定、TTL 25 天收敛、logout 实现、并发多 token | **不进本 map**（写代码不在范围），记入 §7「发现与风险」 |

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
| TC-S05 | fixture 发码 | 连发两个码：旧码登录 + 新码登录 | 旧码 fail；新码 200（F2 覆盖语义） | **P0** | pytest |
| TC-S06 | 无 | 同一 phone 连发 10 次 | 全部 200 → 证明发码无频控（B 层） | P1 | pytest |

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
| TC-L11 | fixture，登录成功 1 次 | 同码第二次登录 | **成功** → 证明验证码非一次性（B 层缺陷证据，F3） | P1 | pytest |
| TC-L12 | TC-L09 的 token | 记录 TTL → 任意请求一次 → 再查 TTL | `TTL(login:token:{token})` 回满值（≈36000 分钟，容忍误差） | P1 | pytest |

### 会话与拦截器层

| 编号 | 前置 | 步骤 | 预期 | 优先级 | 自动化 |
|------|------|------|------|--------|--------|
| TC-L13 | 无 | 无 authorization 头访问受保护接口 | 401 | **P0** | pytest |
| TC-L14 | 无 | 伪造合法格式 token（Redis 无 key） | 401，非 500 | **P0** | pytest |
| TC-L15 | TC-L09 的 token | 直连 `DEL login:token:{token}` 后访问 | 401（状态注入代替 logout，F6） | P1 | pytest |
| TC-L16 | TC-L09 的 token | `authorization: Bearer {token}`（带前缀） | 401（拦截器不剥 Bearer，前端约定裸 token） | P1 | pytest |
| TC-L17 | fixture | 同一 phone 连试 5 次错码 | 5 次全 fail 放行、无锁定无计数 → 证明无暴破防护（B 层） | P1 | pytest |
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
| token 自然过期 | F5：TTL 25 天 + 任意请求滑动续期，等不起；路径与「DEL 注入」重合，由 TC-L15 替代 |
| 真跑 10⁶ 次验证码暴破 | TC-L17 已证明无防护，结论等价 |
| logout 接口本身 | F6：TODO 未实现；实现后补 2 条（登出后旧 token 401、重复登出幂等），见 §7 |
| 同号并发登录多 token | 行为是「每次登录发新 token、旧 token 不失效」，属会话管理设计缺陷延伸，§7 一句话带过 |

## 6. 规模口径

- **23 条独立用例，不向两级缓存链路的 15 条看齐**——按本链路自己的分支密度定（一个「验证码错误」失败点切 3 条不同前置，失败响应等价 ≠ 用例等价）。
- P0 = 主流程（TC-S05 / L09 / L10 / L13 / L14），P1 = 边界与会话，slow 单独标记（仅 TC-L18）。
- 三条链路密度对齐在抢券票走完后统一收口（map fog 项）。

## 7. 发现与风险（C 层，只记录不改造）

1. **发码无频控**（F2）→ 真实项目需按 phone+IP 滑动窗口，本项目已有 `SlidingWindowInterceptor` 载体可挂。
2. **验证码不删**（F3）→ 一次性语义缺失，登录成功应 `DEL login:code:{phone}`。
3. **错码无锁定无计数**（F4+F1）→ 6 位纯数字码 10⁶ 空间，在线暴破可行。
4. **会话永不过期**（F5）→ 25 天 TTL + 全路径滑动续期，实际是「只要用过就永在」。
5. **logout 未实现**（F6）→ 实现后补 2 条用例。
6. **`isCodeInvalid` 死代码**（F10）→ login 不校验码格式；属清理项。
7. **验证码只打日志**（教学设定）→ 真实项目走短信通道；pytest 方案 A 直连 Redis 不受影响。

## 8. 与地图其他票的关系

- **pytest 接口自动化框架的工程结构**（已关闭）：`sms_code` fixture 与状态注入手法是框架层的直接输入。
- **抢券链路测试策略**（进行中）：三判据与三层安全停止线可复用；密度对齐等其关票后统一收口。
