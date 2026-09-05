# hmdp-pro 接口自动化框架 · 工程结构方案

> wayfinder 票「pytest 接口自动化框架的工程结构怎么定」的结论资产。
> 本文所有对被测系统的描述均已对着 `src/main/java` 核实，不凭记忆。
> 本文是**规格**，不是执行记录——搭框架、写代码在 map 之外。

---

## 0. 先看清被测系统（决定了框架的形状）

框架的结构不是凭空设计的，是被被测系统的几个硬事实逼出来的。先把它们摆出来：

| 事实 | 出处 | 对框架的直接影响 |
|---|---|---|
| 登录验证码写 Redis `login:code:{phone}`，TTL 2 分钟，不真发短信 | `UserServiceImpl#sendCode` | **登录不需要短信网关**，pytest 直连 Redis 取码即可完成任意用户登录 |
| 手机号不存在时 `login` 自动建号 | `UserServiceImpl#createUserWithPhone` | **批量造用户零成本**，并发场景要多少有多少，不用预置 |
| token = 32 位无横线 UUID，存 `login:token:{token}` hash，请求头 `authorization` | `UserServiceImpl#login`、`RefreshTokenInterceptor` | fixture 只需管一个字符串；Redis 侧可直接校验/吊销 |
| 未登录被 `LoginInterceptor` 拦成 **HTTP 401 且 body 为空** | `LoginInterceptor` | 断言器必须**分 HTTP 层 / 业务层两级**，401 不能去解 body |
| 业务失败返回 **200 + `code`**，系统故障返回 **503**，限流返回 **429** | `WebExceptionAdvice`、`SlidingWindowInterceptor` | `code` 字段才是业务结论，HTTP 状态码只表达"是否被正确处理" |
| 秒杀下单限流 **5 次/秒/用户**，key `rate:sw:seckill:{userId}` | `SlidingWindowInterceptor` + `application.yaml` | **并发用例要按用户数铺开，不是按请求数**；且每条用例必须前置清配额 |
| 429 的 body 是 `Result.fail(String)`，**`code` 为 null** | `SlidingWindowInterceptor` | 429 只能靠 status + `errorMsg` 断言，不能断言 `code` |
| 秒杀落库是**异步**的（RocketMQ 事务消息 → 消费者落库） | `VoucherOrderServiceImpl#seckillVoucher` | 落库断言**必须 eventually 轮询**，禁止 `sleep` 固定秒 |
| `tb_voucher_order` 有唯一索引 `uk_user_voucher(user_id, voucher_id)` | `hmdp-schema.sql` | 一人一单由 DB 强制；**用例清理不干净，重跑直接 ORDER_REPEAT** |
| 种子券 10 的窗口是 `NOW()-1d ~ NOW()+2d`，在**容器首次启动导入时**求值 | `hmdp-seed-data.sql` | 券会漂移过期 → 抢券用例**必须自造券**，不能吃种子数据 |
| MySQL 宿主机 **3307**（容器 3306 映射）、Redis 6379、应用 8081 | `docker-compose.yml`、`application-local.yaml` | 连接参数全部走配置，不写死 |
| 每个判断分支都暴露成带 tag 的 Prometheus 指标 | `docs/observability.md` | 黑盒可断言内部决策 → **第四类断言助手：指标断言** |

一句话总结：**这个框架的难点不在"发请求"，而在"异步落库的等待"、"限流配额的管理"和"跑第二遍不炸"。** 结构必须为这三件事服务。

---

## 1. 分层：四层 + 一条铁律

### 目录树

```
hmdp-pro/
└─ autotest/                          # Python 测试工程根（进仓库，与 Java 源码平级，边界清楚）
   ├─ pytest.ini                      # 只放 pytest 自身配置：testpaths / markers / addopts
   ├─ requirements.txt
   ├─ README.md                       # 怎么建 venv、怎么跑、报告怎么看
   │
   ├─ config/
   │  ├─ config.yaml                  # 默认值（环境无关部分：超时、轮询参数、手机号段长度）
   │  ├─ env.local.yaml               # 本机：127.0.0.1:8081 / mysql 3307 / redis 6379
   │  └─ env.ci.yaml                  # CI：服务名做 host，端口用容器内网口
   │
   ├─ common/                         # ── util 层：无业务语义，可复用
   │  ├─ client.py                    # ApiClient / ApiResponse
   │  ├─ assertions.py                # 四类断言助手
   │  ├─ db.py                        # DbHelper（PyMySQL）
   │  ├─ redis_helper.py              # RedisHelper
   │  ├─ metrics.py                   # Prometheus 抓取与 delta 计算
   │  ├─ wait.py                      # wait_until（异步落库唯一正解）
   │  ├─ phone_pool.py                # 测试手机号段分配器
   │  ├─ data_loader.py               # yaml → parametrize 数据
   │  └─ keys.py                      # Redis key 构造（与 RedisConstants 对齐）
   │
   ├─ api/                            # ── 接口封装层：1 个接口 1 个函数，只发请求
   │  ├─ user_api.py                  # send_code / login / me
   │  ├─ shop_api.py                  # query_by_id / benchmark_redis / benchmark_db
   │  ├─ voucher_api.py               # add_seckill_voucher / list_of_shop
   │  └─ order_api.py                 # seckill / seckill_result
   │
   ├─ data/                           # ── 数据层：用例与数据分离，数据驱动
   │  ├─ login_cases.yaml
   │  ├─ cache_cases.yaml
   │  └─ seckill_cases.yaml
   │
   ├─ testcases/                      # ── 用例层：只有在这里做断言
   │  ├─ conftest.py                  # 全部 fixture 在此定义
   │  ├─ test_login.py
   │  ├─ test_cache.py
   │  └─ test_seckill.py
   │
   ├─ ui/                             # Playwright UI 自动化（本票不展开，预留位置）
   └─ reports/                        # gitignore：allure-results / html / 截图
```

### 铁律：api 层不做业务断言

```
case 层（断言、场景编排、并发编排）
   ↓ 调用
api 层（拼 URL、发 HTTP、返回 ApiResponse）
   ↓ 使用
common 层（client / db / redis / wait / assertions）
```

- **api 层**的函数签名是 `f(client, ...) -> ApiResponse`，函数里**只有一次 HTTP 调用**，不 assert、不 sleep、不重试。
- 唯一例外：api 层可以做**协议级**断言（如"这个接口应当返回 JSON"，解析失败直接抛 `ApiProtocolError`），因为那是 HTTP 契约，不是业务结论。
- 业务结论（库存不足？重复下单？缓存命中哪一层？）**一律在 case 层**用断言助手表达。

> 面试讲法：api 层管"怎么调"，case 层管"期望什么"，common 层管"怎么验"。三层职责不重叠，接口改 URL 只动 api 层一个函数，业务规则变只动 data 层一个 yaml。

---

## 2. 鉴权与登录态

### 登录是这个项目里唯一"零外部依赖"的被测链路

验证码落在 Redis 且 TTL 2 分钟，所以完整登录流程在 pytest 里是**三次动作**：

```python
def login_as(phone, http, redis_cli, cache):
    key = f"login:code:{phone}"
    http.post("/user/code", params={"phone": phone})          # ① 触发发码
    code = redis_cli.wait_key(key, timeout=2.0)                # ② 直连 Redis 取码
    r = http.post("/user/login", json={"phone": phone, "code": code})
    assert r.http_status == 200 and r.data, f"登录失败: {r.body}"
    return AuthContext(phone=phone, token=r.data,
                       user_id=r.data_user_id(http),
                       headers={"authorization": r.data})
```

`user_id` 从 `GET /user/me` 拿（或 Redis `hget login:token:{token} id`——走 Redis 更快且能顺带校验 token 落库形态，推荐后者，因为它同时是"登录态正确写入 Redis"的一条断言）。

### fixture 分层

| fixture | scope | 作用 |
|---|---|---|
| `cfg` | session | 配置门面 |
| `http` | session | `requests.Session`，复用连接 |
| `db` / `redis_cli` | session | 中间件句柄 |
| `token_cache` | session | `{phone: AuthContext}` 缓存 |
| `login` | function | 工厂：`login(phone) -> AuthContext`，带缓存 |
| `user` | function | 默认单用户（`cfg.phone_pool[0]`，取种子 `13800000001`） |
| `user_pool` | function | 工厂：`user_pool(n) -> list[AuthContext]` |
| `reset_rate_limit` | function | 工厂：`reset_rate_limit(user_id)`，`DEL rate:sw:seckill:{uid}` |

**为什么 `login` 是 function scope 而 `token_cache` 是 session scope**：缓存要跨用例复用（省掉每次登录 3 次请求），但工厂本身要能被 teardown 钩子（如 `token_cache.invalidate(phone)`）操作。

### token 缓存与失效

- 命中：`login(phone)` 先查 `token_cache`，命中直接返回，不发请求。
- 失效（两条触发路径）：
  1. **主动**：用例测"登出 / token 过期 / 篡改 token"时必须 `token_cache.invalidate(phone)`，否则下一条用例拿到脏 token。
  2. **被动**：`ApiClient` 检测到响应 `http_status == 401` 时，抛 `TokenExpired(phone)`；`login` 工厂捕获后 invalidate + 重新登录 + 原请求重试一次（**只重试一次**，二次仍 401 就真失败）。
- 注意：token TTL 是 **36000 分钟（25 天）**，且 `RefreshTokenInterceptor` 每次请求都会续期，所以正常跑不会自然过期。失效处理是为了防御性正确，不是为了兜底常事。

### 多用户 fixture：并发场景的刚需

这不是"要不要"的问题，是被限流逼出来的：

> 单用户 5 次/秒。要并发打 100 个抢券请求，**至少需要 20 个用户**（`ceil(100/5)`），否则超出的请求全被 429 拦掉，你测的就不是并发正确性，而是限流器。

```python
@pytest.fixture
def user_pool(login, cfg):
    def _make(n: int) -> list[AuthContext]:
        return [login(p) for p in cfg.phone_pool.take(n)]
    return _make
```

`phone_pool` 分配器：
- 号段 `138` + 8 位（正则 `^1([38][0-9]|...)\d{8}$` 允许 130–139 / 180–189），例如 `13800100000`–`13800100999`。
- 分配是**幂等且可预测**的：按序号发号，不随机。用例报失败时能从手机号反推是第几个用户。
- 手机号不存在时后端自动建号，所以**不需要任何预置 SQL**。

---

## 3. 配置与环境隔离

### 三层覆盖，职责分开

```
env.<profile>.yaml  (入库，放地址/端口/默认账号)
        ↓ 被覆盖
环境变量 HMDP_*     (不入库，放密码等敏感项 + CI 临时改写)
        ↓ 被覆盖
命令行 --env / --base-url
```

**关键决策：pytest.ini 只管 pytest 自己，业务配置另放 yaml。**
`pytest.ini` 里只写 `testpaths` / `markers` / `addopts` / `log_cli`。连接串塞进 ini 是新手最常见的坏味道——ini 没有嵌套结构、没有环境变量插值，很快会失控。

`config.yaml`（默认，入库）：
```yaml
http:   { timeout: 5.0, retry_on_401: 1 }
wait:   { default_timeout: 5.0, interval: 0.2 }     # 异步落库轮询参数
phone:  { prefix: "138001", start: 0, width: 5 }    # 号段 13800100000 起
seckill:{ stock_default: 100 }
```

`env.local.yaml`（入库，无敏感信息）：
```yaml
base_url: "http://127.0.0.1:8081"
mysql:
  host: 127.0.0.1
  port: 3307                    # 容器 3306 → 宿主机 3307
  user: ${HMDP_DB_USER:-root}
  password: ${HMDP_DB_PASSWORD:-}   # 真正的值走环境变量，文件里不落
  database: hmdp
  charset: utf8mb4
redis:  { host: 127.0.0.1, port: 6379, db: 0 }
prometheus: "http://127.0.0.1:9090"     # 也可直接打应用的 /actuator/prometheus
```

`env.ci.yaml`：host 换成 compose 服务名（`mysql:3306`、`redis:6379`），端口回到容器内网口。

### 同一套用例跑 local 与 CI

- 选择靠 `pytest --env=ci`（`pytest_addoption` 注册），不靠改代码。
- **用例里禁止出现任何硬编码地址**，一律 `cfg.xxx`。
- **统一时区**：MySQL 连接必须显式带 `serverTimezone=Asia/Shanghai`。项目里踩过——容器 TZ、连接串、JVM 三者不一致会让 `begin_time` 差 8 小时，秒杀窗口断言直接翻车。测试侧同样的坑，配置里写死。

---

## 4. 断言助手：三类通用 + 一类项目特色

统一入口 `assertions.py`，全部**先解 HTTP 层，再解业务层**。

### 4.1 业务码断言

```python
def assert_result(resp: ApiResponse, *,
                  http_status: int = 200,     # 401 / 429 / 503 从这里判
                  success: bool | None = None,
                  code: int | None = None,    # ErrorCode 数字码；None 表示"不校验"
                  msg_contains: str | None = None,
                  data_check: Callable[[Any], bool] | None = None) -> Any
```

必须处理三种"非标准 body"：

| 场景 | HTTP | body | 断言方式 |
|---|---|---|---|
| 业务失败 | 200 | `{success:false, code:1003, errorMsg:"库存不足"}` | 断言 `code == 1003` |
| 未登录 | **401** | **空**（拦截器只 setStatus） | 只断言 `http_status`，**不碰 body** |
| 被限流 | **429** | `{success:false, code:null, errorMsg:"请求过于频繁..."}` | 断言 `http_status` + `msg_contains` |
| 系统故障 | **503** | `{success:false, code:5002, ...}` + 响应头 `X-Trace-Id` | 断言 `code`，并把 `trace_id` 挂到 allure 报告 |

`ApiResponse.trace_id` 从响应头 `X-Trace-Id` 取——`WebExceptionAdvice` 只在系统故障时写这个头，正好用来串联 Java 侧日志，是"跨语言排障"的一个亮点。

错误码常量在 `common/error_codes.py` 里镜像一份（`STOCK_OUT = 1003`、`ORDER_PROCESSING = 1100`…），**并注明与 `ErrorCode.java` 一一对应**，改 Java 侧要同步。

### 4.2 DB 侧断言

```python
def assert_db(db: DbHelper, sql: str, params: dict | None = None, *,
              expected: Any | None = None,        # 精确等值（单行单列）
              check: Callable[[list[dict]], bool] | None = None,
              timeout: float = 0) -> list[dict]    # timeout>0 时启用 eventually
```

常用封装（直接写进 `assertions.py`）：
- `assert_order_exists(db, user_id, voucher_id, timeout=5.0)`
- `assert_order_count(db, voucher_id, expected, timeout=5.0)`
- `assert_stock(db, voucher_id, expected, timeout=5.0)`

**`timeout` 参数是这一类的灵魂**：秒杀落库是异步的，`assert_order_exists(..., timeout=5.0)` 内部走 `wait_until` 轮询，而不是 `sleep(2)` 然后查一次。

### 4.3 Redis 侧断言

```python
def assert_redis(r: RedisHelper, key: str, *,
                 value: str | None = None,
                 check: Callable[[str | None], bool] | None = None,
                 ttl_range: tuple[int, int] | None = None,
                 absent: bool = False,
                 timeout: float = 0)
```

常用封装：
- `assert_stock_key(r, voucher_id, expected)` → `seckill:stock:{vid}`
- `assert_in_seckill_order_set(r, voucher_id, user_id)` → `sismember seckill:order:{vid}`
- `assert_cache_shop(r, shop_id, *, present=True)` → `cache:shop:{sid}`
- `assert_login_token(r, token, *, present=True)`

key 一律由 `common/keys.py` 构造，**与 `RedisConstants.java` 对齐**并注明来源，禁止在用例里拼字符串。

### 4.4 指标断言（本项目特色，第四类）

这是"用 Python 测 Java 项目不吃亏"的兑现点：内部每个分支都带 tag 计数，黑盒抓 `/actuator/prometheus` 就能断言系统**自己认为发生了什么**，与接口返回值、DB 状态三方交叉验证。

```python
with metrics.snapshot() as snap:                  # 进入时抓一次
    ...  # 执行操作
snap.delta_ge("hmdp_seckill_result_total", {"reason": "stock_out"}, 1)
snap.delta_eq("hmdp_cache_hit_total", {"level": "l1"}, 1)
snap.assert_no_increase("hmdp_ratelimit_fallback_total")   # 限流器没裸奔
```

指标名到 Prometheus 后缀的换算（`hmdp.seckill.result` → `hmdp_seckill_result_total`）由 `metrics.py` 封装，用例只写点分名。

---

## 5. 报告与可重复执行

### 报告：allure 为主，pytest-html 为快查

| | allure-pytest | pytest-html |
|---|---|---|
| 分层展示 | feature / story / severity / step | 平铺表格 |
| 附件 | 响应体、traceId、DB 与 Redis 快照、截图 | 有限 |
| 趋势 | 历史趋势（需存 history） | 无 |
| 成本 | 需额外装 allure CLI 才能出 HTML | 零依赖 |

**结论：用 allure。** 它是测开岗的默认语言，报告本身也是作品集的一部分——面试时能打开一个带步骤、带附件的报告，比说"我会 pytest" 强得多。开发调试时用 `--html=reports/quick.html` 快速看，正式产出用 allure。

必挂的 allure 附件（`common/client.py` 与 conftest 里自动挂，不用每个用例手写）：
- 请求：`method / url / headers（token 打码）/ body`
- 响应：`http_status / body / X-Trace-Id`
- 失败时追加：`wait_until` 的最后一轮快照、相关 Redis key 值、相关 DB 行

### 可重复执行：三条纪律

**纪律一：用例自带造数，不吃存量数据。**
抢券用例**必须自造秒杀券**——种子券 10 的窗口在容器首次启动导入时按 `NOW()` 算，跑久了会漂移到"已结束"，用例变成随机失败。

```python
@pytest.fixture
def new_seckill_voucher(http, db):
    @contextmanager
    def _make(shop_id=1, stock=100, begin_offset_s=-60, end_offset_s=3600):
        vid = http.post("/voucher/seckill", json={...}).data   # 无需登录（/voucher/** 已放行）
        db.execute("UPDATE tb_seckill_voucher SET stock=%s, begin_time=DATE_ADD(NOW(), INTERVAL %s SECOND), "
                   "end_time=DATE_ADD(NOW(), INTERVAL %s SECOND) WHERE voucher_id=%s", ...)
        yield vid
        # teardown：订单 → 秒杀券行 → 券行 → Redis 相关 key，按依赖倒序删
    return _make
```

**纪律二：造数与清理严格对称。**
teardown 必须删干净，否则重跑直接翻车（`uk_user_voucher` 唯一索引会把第二次下单打成 `ORDER_REPEAT`）：
```
tb_voucher_order（该券的行）→ tb_seckill_voucher → tb_voucher
Redis: seckill:stock:{vid} / seckill:order:{vid} / seckill:meta:{vid} /
       seckill:claim:{vid} / seckill:txn:{vid} / seckill:queue:{orderId} / rate:sw:seckill:{uid}
```
teardown 用 `try/finally`，**清理失败要 warn 不要 raise**（否则掩盖真正的用例失败）。

**纪律三：异步落库只许 eventually，不许 sleep。**
```python
def wait_until(predicate, timeout=5.0, interval=0.2, desc="") -> Any:
    """轮询 predicate，超时抛 AssertionError（把最后一次快照写进消息）。"""
```
禁止 `time.sleep(3)`——CI 机器慢一点就 flaky，快一点又浪费时间。所有落库/库存/缓存断言都走 `timeout=` 参数。

### 额外一条：并发用例不能用 pytest-xdist 并行

`pytest-xdist` 是**进程级并行跑不同用例**，抢券并发是**用例内部用 `ThreadPoolExecutor` 打 N 个请求**——两回事，别混。而且抢券类用例共享同一张券和同一批用户，xdist 并行会互相踩，这些用例要打 `@pytest.mark.serial`，用 `-n` 时排除掉。

---

## 6. 版本与依赖

### Python：用 managed 3.13.12

- venv 建在 **`autotest/.venv`**（工程内，IDE 自动识别），用 managed 解释器创建：
  ```
  C:\Users\luckyone\.workbuddy\binaries\python\versions\3.13.12\python.exe -m venv autotest/.venv
  ```
- 解释器选 3.13.12（managed）的理由：隔离、不污染系统 Python；本项目依赖都是纯 Python 或有 3.13 轮子。
- 兜底：若某个包在 3.13 上装不上，**换 3.10.8 重建 venv 是零成本操作**（venv 不进仓库，删了重建即可）。`requirements.txt` 与 README 声明 `requires-python >= 3.10`，不锁死。
- **不要**把 venv 建在 `.workbuddy/` 下——那是项目数据目录，会被当成 WorkBuddy 内部文件。

### .gitignore 追加

```gitignore
### Python 接口自动化 ###
autotest/.venv/
autotest/reports/
autotest/allure-results/
autotest/.pytest_cache/
__pycache__/
*.py[cod]
```

### requirements.txt

```
# 运行
pytest>=8.3
pytest-html>=4.1            # 本地快查报告
allure-pytest>=2.13         # 正式报告（需另装 allure CLI 才能出 HTML）
pytest-rerunfailures>=14.0  # 仅用于网络抖动兜底，见下方红线
requests>=2.32
PyMySQL>=1.1
redis>=5.0
PyYAML>=6.0

# 可选
pytest-xdist>=3.6           # 独立用例加速；serial 标记用例自动排除
```

红线两条：
1. **不引 tenacity**：`wait_until` 自己写 20 行，少一个依赖，面试时能讲清实现。
2. **`pytest-rerunfailures` 只能全局配 `--reruns=1 --reruns-delay=1`，且禁止给 flaky 用例单独加 `@pytest.mark.flaky`** —— 重试是网络抖动的兜底，不是掩盖异步等待写错的遮羞布。写了 sleep 的地方就该改 wait_until，不该加重试。

Playwright 依赖单独放 `autotest/ui/requirements-ui.txt`，不混进主依赖（本票不展开 UI）。

---

## 7. 关键 fixture 与助手签名汇总

```python
# ---------- common/client.py ----------
@dataclass
class ApiResponse:
    http_status: int
    body: dict | None            # 401 时可能为 None
    headers: dict
    @property
    def code(self) -> int | None
    @property
    def data(self) -> Any
    @property
    def error_msg(self) -> str | None
    @property
    def trace_id(self) -> str | None     # 响应头 X-Trace-Id

class ApiClient:
    def request(self, method, path, *, params=None, json=None,
                headers=None, timeout=None) -> ApiResponse
    def post(self, path, **kw) -> ApiResponse
    def get(self, path, **kw) -> ApiResponse

# ---------- 断言（common/assertions.py）----------
def assert_result(resp, *, http_status=200, success=None, code=None,
                  msg_contains=None, data_check=None) -> Any
def assert_db(db, sql, params=None, *, expected=None, check=None, timeout=0) -> list[dict]
def assert_redis(r, key, *, value=None, check=None, ttl_range=None, absent=False, timeout=0)
def assert_order_exists(db, user_id, voucher_id, *, timeout=5.0) -> dict
def assert_stock(db, voucher_id, expected, *, timeout=5.0)
def assert_stock_key(r, voucher_id, expected, *, timeout=5.0)

# ---------- common/wait.py ----------
def wait_until(predicate, *, timeout=5.0, interval=0.2, desc="") -> Any

# ---------- common/metrics.py ----------
class MetricsSnapshot:                 # 用法 with metrics.snapshot() as snap:
    def delta(self, name: str, tags: dict | None = None) -> float
    def delta_ge(self, name, tags, n) / delta_eq(self, name, tags, n)
    def assert_no_increase(self, name, tags=None)

# ---------- testcases/conftest.py ----------
def pytest_addoption(parser):          # --env=local|ci，--base-url
cfg / http / db / redis_cli / metrics          # session scope
token_cache                                    # session scope
login(phone) -> AuthContext                    # function scope 工厂
user -> AuthContext                            # function scope，默认种子用户
user_pool(n) -> list[AuthContext]              # function scope 工厂
reset_rate_limit(user_id)                      # function scope，DEL rate:sw:seckill:{uid}
new_seckill_voucher(shop_id, stock, begin_offset_s, end_offset_s)   # contextmanager
```

`AuthContext`：`phone / user_id / token / headers`，`headers` 可直接 `**` 展开传给 api 层。

---

## 8. 必踩的九个坑（写进框架，别写进教训）

| # | 坑 | 框架侧的防御 |
|---|---|---|
| 1 | 秒杀限流 5 次/秒/用户，上一条用例烧掉配额 → 下一条莫名 429 | 每条秒杀用例开头 `reset_rate_limit`；并发请求数 `> 5` 时强制按用户铺开 |
| 2 | 落库异步，立刻查 DB 查不到 | 所有落库断言带 `timeout`，内部 `wait_until` |
| 3 | 401 空 body，解析 JSON 直接 KeyError | `ApiResponse.body` 允许为 None；`assert_result` 分两层 |
| 4 | 429 body 的 `code` 是 null | 429 只断言 status + `msg_contains` |
| 5 | 唯一索引 `uk_user_voucher` 让重跑变 `ORDER_REPEAT` | teardown 必须删订单行，按依赖倒序 |
| 6 | 种子券窗口 `NOW()` 在导入时求值，会漂移到"已结束" | 抢券用例自造券 |
| 7 | MySQL 时区不一致 → 时间窗差 8 小时 | 连接串写死 `serverTimezone=Asia/Shanghai` |
| 8 | Redis 配了 `volatile-lru` + 256MB | 测试别灌大量带 TTL 的 key；`seckill:stock:*` 无 TTL 不会被逐，可放心断言 |
| 9 | xdist 并行让共享数据的用例互相踩 | 抢券/缓存用例打 `serial` 标记，`-n` 时排除 |

---

## 9. 本票没有回答、留给后续的问题

- **JMeter 脚本与 pytest 的分工**：本方案只管 pytest。JMeter 负责性能基线，pytest 负责功能正确性，两者用例不共用。是否让 JMeter 复用 pytest 造的数据（券 id、token 列表），等「抢券链路测试策略」那张票定。
- **Playwright UI 的结构**：`autotest/ui/` 位置已预留，目录内部怎么切（page object 与否）不在本票范围。
- **CI 用哪个平台**：GitHub Actions 需要能起 docker-compose（MySQL/Redis/RocketMQ），可行性与耗时未验证——这决定 `env.ci.yaml` 的最终形态。
