# 登录链路面试弹药库

这套材料只描述仓库当前已经实现并验证的能力。项目场景是校园餐饮评价与优惠平台，登录方式为手机号验证码登录。

## 阅读顺序

1. [全链路原理](./01-login-chain.md)：先建立请求、Redis 状态和拦截器的整体图。
2. [自动化接口测试设计与用例](./02-test-design.md)：查看流程分析、29 条接口用例、观察点与稳定性设计。
3. [缺陷闭环](./03-defect-stories.md)：准备最有区分度的测试开发故事。
4. [面试口述与追问](./04-interview-script.md)：练习 30 秒、2 分钟和深挖回答。
5. [一页速记](./05-cheatsheet.md)：面试前快速复习。
6. [简历描述](./06-resume-copy.md)：可直接粘贴到项目经历。

## 已验证结果

- 登录链路历史基线：23/23 通过。
- 当前登录套件：29 条已通过 pytest 收集校验；新增 6 条等待依赖环境启动后实跑。
- 历史 pytest 全量回归：50/50 通过（扩充登录套件前）。
- Java TraceContext 单测：6/6 通过。
- 报告：`autotest/reports/login-report.html`。

## 证据入口

- 业务实现：`src/main/java/com/hmdp/service/impl/UserServiceImpl.java`
- 原子验证码脚本：`src/main/resources/verify_login_code.lua`
- 会话刷新：`src/main/java/com/hmdp/utils/RefreshTokenInterceptor.java`
- 自动化用例：`autotest/testcases/test_login_chain.py`
