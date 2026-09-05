# login2.html 移除规格（密码登录砍掉）

- 日期：2026-09-05
- 决定人：怡霖
- 关联票：「密码登录页的存废：login2.html 当前必然失败」（Wayfinder map「三条链路改造规格与测试策略」子票）

## 决定

**砍掉密码登录，验证码登录是唯一登录入口。**

## 改动清单

| # | 动作 | 位置 |
|---|------|------|
| 1 | 整文件删除 | `hm-dianping-frontend/html/hmdp/login2.html` |
| 2 | 整行删除承载链接的 div（不留空壳） | `hm-dianping-frontend/html/hmdp/login.html:36` `<div ...><a href="/login2.html">密码登录</a></div>` |
| 3 | 删除 `password` 字段（`login()` 从不读它，砍掉密码登录后无任何读取方；保留会误导读者以为支持密码登录） | `src/main/java/com/hmdp/dto/LoginFormDTO.java:9` |
| 4 | 整文件删除（全项目零调用方，grep 核实） | `src/main/java/com/hmdp/utils/PasswordEncoder.java` |

**保留不动**：`tb_user.password` 列、`User.password` 实体字段、种子数据。删列要动 DDL + seed，无功能收益；实体字段与列共存无害。可选：在列注释标注「保留但未使用」。

## 不改的部分

- 后端唯一登录入口仍是 `POST /user/login`（验证码），**无接口变更**，`docs/api.md` 无需同步。
- 登录链路 23 条 pytest 用例照旧——本就基于验证码流程，零调整。
- Playwright UI 自动化：login2.html 由「冻结待拍板」转为**永久不测**（页面删除后连冻结对象都不存在）。

## 依据（2026-09-05 对着源码核实）

- 前端引用 `login2.html` 的只有 `login.html:36` 一处（`logs/access.log` 是 2022 年历史日志，不算引用）。
- `login2.html` 自身的出链（→ `login.html`、成功跳 `/info.html`）随页面删除一并消失，无其他页面受影响。
- `UserServiceImpl.login()` 只读 `loginForm.getCode()`；`LoginFormDTO.password` 无读取方；`PasswordEncoder` 无调用方。
- 种子数据 `hmdp-seed-data.sql:24-27` 四个用户 `password` 全为空串 `''`。

## 否决「补后端」的理由（留档，勿翻案）

机械改动小（~30 行：login() 加密码分支），但真实代价不成比例：

1. **密码从哪来是前置产品决策**：种子用户 password 全空串，要回答注册设密 / 手工补密 / 未设密用户走密码登录返回什么。
2. **指标纪律是硬约束**：新分支（密码空 / 用户不存在 / 密码错 / 成功）须暴露为带 tag 指标 → 新立指标契约票。
3. **已关票重开**：登录链路测试策略（23 条用例）按「验证码唯一入口」定稿，补密码线要扩用例、改文档、重开票。
4. **安全面翻倍**：现状已核实发码无频控、连错无锁定；密码暴破空间远大于 6 位验证码，答辩要多解释一倍缺口。
5. **叙事价值 ≈ 0**：本项目卖点是「分支可观测 + 三链路测试策略」，密码登录是最平淡的校验分支，不增加可测性叙事，反而稀释。
