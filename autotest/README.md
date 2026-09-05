# hmdp-pro 接口自动化测试工程（pytest）

> 结构规格：`docs/plans/2026-09-05-pytest-framework-structure.md`（wayfinder 已关票决议）。
> 三链路用例策略：登录 `docs/plans/2026-09-05-login-chain-test-strategy.md` /
> 两级缓存 `docs/plans/2026-09-05-two-level-cache-test-strategy.md` /
> 抢券 `docs/plans/2026-09-05-seckill-chain-test-strategy.md`。

## 目录结构（四层 + 一条铁律）

```
autotest/
├─ pytest.ini          # 只放 pytest 自身配置；业务配置走 config/*.yaml
├─ config/             # config.yaml(默认) + env.local.yaml / env.ci.yaml(环境)
├─ common/             # util 层：client / assertions / db / redis / metrics / wait / phone_pool / keys
├─ api/                # 接口封装层：1 个接口 1 个函数，只发请求，不做业务断言
├─ data/               # 数据层：yaml → parametrize（三链路用例票填充）
├─ testcases/          # 用例层：只有在这里做断言（conftest.py = 全部 fixture）
├─ ui/                 # Playwright（预留，独立依赖 requirements-ui.txt）
└─ reports/            # gitignore：allure-results / html / 截图
```

**铁律：api 层不做业务断言。** api 层管「怎么调」，case 层管「期望什么」，common 层管「怎么验」。
接口改 URL 只动 api 层一个函数，业务规则变只动 data 层一个 yaml。

## 环境搭建

```bash
# Windows（managed Python 3.13.12；venv 建在工程内，IDE 自动识别）
C:/Users/luckyone/.workbuddy/binaries/python/versions/3.13.12/python.exe -m venv autotest/.venv
autotest/.venv/Scripts/python -m pip install -r autotest/requirements.txt
```

前置：`docker compose up`（MySQL 3307 / Redis 6379 / RocketMQ / Prometheus）+ 应用本地 profile 起在 8081。

## 运行

```bash
cd autotest
.venv/Scripts/python -m pytest                        # 全量（当前=框架自检）
.venv/Scripts/python -m pytest -m "not slow"          # 跳过长等待用例
.venv/Scripts/python -m pytest --env=local            # 换环境 profile（默认 local）
.venv/Scripts/python -m pytest --base-url=http://127.0.0.1   # 临时切网关入口
.venv/Scripts/python -m pytest --html=reports/quick.html     # 快查报告
.venv/Scripts/python -m pytest --alluredir=reports/allure-results && allure serve reports/allure-results
```

markers：`slow`（确定性长等待）/ `serial`（xdist 时排除）/ `chaos`（阻塞全局 Redis，禁并发）/ `isolate`（指标窗口隔离）。

## 三条纪律（可重复执行的根）

1. **用例自带造数**：抢券用 `new_seckill_voucher` 动态造券，不吃种子数据（种子券窗口会漂移过期）；
2. **造数与清理对称**：teardown 按依赖倒序删（订单 → 秒杀券 → 券 → Redis key），失败 warn 不 raise；
3. **异步落库只许 eventually**：`wait_until` 轮询真实状态，禁 `time.sleep`。唯一例外是 TTL/熔断的确定性等待（标 slow）。

并发用例用例内 `ThreadPoolExecutor` 打 N 请求——**不是** pytest-xdist；共享数据的用例打 `serial` 标记。

## 指标断言的两条纪律

- 结果型指标（一次一计、取值互斥，如 `hmdp.seckill.result{reason}`）可断 `== 1`；
  路径型（可叠加多次，如 `hmdp.cache.hit{level}`）只能 `>= 1`；
- 指标是辅助不是主依据（循环论证），主依据 = HTTP 响应 + 直连 Redis/MySQL。

## 依赖红线

- 不引 tenacity：`wait_until` 自己写；
- rerunfailures 只全局配 `--reruns=1 --reruns-delay=1`（已在 pytest.ini），禁止单用例加 flaky 标记——
  重试是网络抖动兜底，不是掩盖 wait_until 写错的遮羞布。

## 配置三层覆盖

`config/env.<profile>.yaml`（入库）← 环境变量 `${HMDP_*}`（密码不落文件）← 命令行 `--env` / `--base-url`。
用例里禁止出现任何硬编码地址，一律 `cfg.xxx`。
