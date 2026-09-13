"""全部 fixture 在此定义（框架结构票 §2/§7 + 三张链路策略票的 fixture 规范）。

fixture 分层：
| fixture                              | scope    | 作用 |
|--------------------------------------|----------|------|
| cfg / http / db / redis_cli / metrics | session | 配置门面 / HTTP / MySQL / Redis / 指标 |
| phone_pool / token_cache             | session | 号段分配器 / {phone: AuthContext} 缓存 |
| login / user / user_pool             | function | 登录工厂 / 默认种子用户 / 并发多用户工厂 |
| reset_rate_limit / sms_code          | function | 限流配额清理 / 发码取码（登录链路规范） |
| new_seckill_voucher                  | function | contextmanager 造券（可重复性的根） |

为什么 login 是 function scope 而 token_cache 是 session scope：缓存要跨用例复用
（省掉每次登录 3 次请求），但工厂本身要能被 teardown 钩子操作（invalidate）。
"""
from __future__ import annotations

import logging
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.request
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta
from typing import List

import pytest

from api import order_api, shop_api, user_api, voucher_api
from common import keys
from common.client import ApiClient, AuthContext, AuthedClient
from common.config import Config
from common.db import DbHelper
from common.metrics import MetricsHelper
from common.phone_pool import PhonePool
from common.redis_helper import RedisHelper
from common.wait import wait_until

log = logging.getLogger(__name__)


# ============================================================ 命令行选项 ====
def pytest_addoption(parser):
    parser.addoption(
        "--env", default="local", choices=["local", "ci"],
        help="环境 profile（读 config/env.<profile>.yaml）",
    )
    parser.addoption(
        "--base-url", default=None,
        help="覆盖 base_url（最高优先级；如切 OpenResty 网关入口 http://127.0.0.1）",
    )


def pytest_collection_modifyitems(config, items):
    """坑 #9 的自动防线：xdist 并行（-n）时排除 serial/chaos 用例。

    抢券/缓存用例共享同一张券、同一批用户、全局 Redis——xdist 进程级并行会互相踩；
    chaos（DEBUG SLEEP）会阻塞整个 Redis 事件循环，必须独占跑。
    用例打了标记就自动排除，不靠人记得加 -m。
    """
    if config.getoption("-n", default=None):   # -n 未传时不排除
        skip = pytest.mark.skip(reason="xdist 并行下自动排除（serial/chaos 用例必须独占跑）")
        for item in items:
            if "serial" in item.keywords or "chaos" in item.keywords:
                item.add_marker(skip)


# ============================================================ session 层 ====
@pytest.fixture(scope="session")
def cfg(request) -> Config:
    return Config(
        profile=request.config.getoption("--env"),
        base_url_override=request.config.getoption("--base-url"),
    )


@pytest.fixture(scope="session")
def http(cfg) -> ApiClient:
    client = ApiClient(cfg.base_url, timeout=float(cfg.http.timeout))
    yield client
    client._session.close()


@pytest.fixture(scope="session")
def db(cfg) -> DbHelper:
    # **cfg.mysql：连接参数以 yaml 为唯一事实源，DbHelper 内部做类型收敛
    helper = DbHelper(**cfg.mysql)
    yield helper
    helper.close()


@pytest.fixture(scope="session")
def redis_cli(cfg) -> RedisHelper:
    helper = RedisHelper(**cfg.redis)
    yield helper
    helper.close()


@pytest.fixture(scope="session")
def metrics(cfg) -> MetricsHelper:
    # 默认直连应用 /actuator/prometheus：无 scrape_interval 滞后，delta 即时。
    # 要走 Prometheus 服务端时把 scrape_url 换 cfg.prometheus 再 + 必要的 query 参数。
    return MetricsHelper(
        scrape_url=f"{cfg.base_url}/actuator/prometheus",
        application=cfg.metrics.application,
    )


@pytest.fixture(scope="session")
def phone_pool(cfg) -> PhonePool:
    """号段分配器必须 session 级：跨用例连续编号才幂等可预测。"""
    return PhonePool(prefix=cfg.phone.prefix, start=int(cfg.phone.start), width=int(cfg.phone.width))


class TokenCache:
    """{phone: AuthContext}。测「登出/过期/篡改 token」的用例 teardown 必须 invalidate。"""

    def __init__(self):
        self._cache: "dict[str, AuthContext]" = {}

    def get(self, phone: str):
        return self._cache.get(phone)

    def put(self, phone: str, ctx: AuthContext) -> None:
        self._cache[phone] = ctx

    def invalidate(self, phone: str) -> None:
        self._cache.pop(phone, None)


@pytest.fixture(scope="session")
def token_cache() -> TokenCache:
    return TokenCache()


# ============================================================ function 层 ====
@pytest.fixture
def login(http, redis_cli, token_cache):
    """登录工厂：login(phone) -> AuthContext，命中缓存直接返回（不发请求）。

    完整登录三动作（本链路零外部依赖的根据）：发码 → 直连 Redis 取码 → login。
    user_id 走 Redis hget login:token:{token}——比 /user/me 快，且同时断言了
    「登录态正确写入 Redis hash」。
    """
    def _do_login(phone: str, into: AuthContext | None = None) -> AuthContext:
        # 上一次 pytest 进程可能已登录并消费 code，但 60s cooldown 仍在。
        # 此时 /user/code 会幂等返回成功却不补发，导致新进程永远取不到验证码。
        if not redis_cli.exists(keys.login_code(phone)):
            redis_cli.delete(keys.login_code_cooldown(phone))
        resp = user_api.send_code(http, phone)
        assert resp.http_status == 200 and resp.body.get("success"), f"发码失败: {resp.body}"
        code = redis_cli.wait_key(keys.login_code(phone))
        resp = user_api.login(http, phone, code)
        assert resp.http_status == 200 and resp.data, f"登录失败: {resp.body}"
        token = resp.data
        user_id = redis_cli.hget(keys.login_token(token), "id")
        assert user_id, f"token hash 缺 id 字段: {keys.login_token(token)}"
        if into is not None:
            into.refresh(token, int(user_id))     # 原地更新，token_cache 里的对象保持有效
        else:
            into = AuthContext(phone=phone, user_id=int(user_id), token=token,
                               headers={"authorization": token})
        into.client = AuthedClient(http, into, relogin=lambda ctx=into: _relogin(ctx))
        token_cache.put(phone, into)
        return into

    def _relogin(ctx: AuthContext) -> None:
        """AuthedClient 的 401 回调：失效缓存 → 重登 → 原地 refresh（只重试一次）。"""
        token_cache.invalidate(ctx.phone)
        _do_login(ctx.phone, into=ctx)

    def _login(phone: str) -> AuthContext:
        cached = token_cache.get(phone)
        if cached is not None:
            return cached
        return _do_login(phone)

    return _login


@pytest.fixture
def user(login, cfg) -> AuthContext:
    """默认单用户：种子用户 1「小鱼同学」（hmdp-seed-data.sql，id=1）。"""
    return login(cfg.user.default_phone)


@pytest.fixture
def user_pool(login, phone_pool):
    """并发多用户工厂——不是可选项，是被限流逼出来的刚需：
    单用户领券 5 次/秒，并发打 100 个请求至少 20 个用户，否则测的是限流器不是并发正确性。
    """
    def _make(n: int) -> List[AuthContext]:
        return [login(p) for p in phone_pool.take(n)]
    return _make


@pytest.fixture
def reset_rate_limit(redis_cli):
    """每条领券用例开头清配额（坑 #1：上一条用例烧掉的配额会让下一条莫名 429）。
    提交（5 次/秒）与结果查询（10 次/秒）两个桶独立，一起清。
    """
    def _reset(user_id: int) -> None:
        redis_cli.delete(keys.rate_sw_seckill(user_id), keys.rate_sw_seckill_result(user_id))
    return _reset


@pytest.fixture
def sms_code(http, redis_cli):
    """登录链路 fixture 规范（登录策略票 §4 方案 A）：
    发码 → 直连 Redis 取码 → 顺手断言 TTL ≈ 120s（一次发码，fixture 内不重复发，
    覆盖语义的用例 TC-S05 自己再发）。
    """
    def _make(phone: str) -> str:
        redis_cli.delete(
            keys.login_code_cooldown(phone),
            keys.login_attempts(phone),
            keys.login_locked(phone),
        )
        resp = user_api.send_code(http, phone)
        assert resp.http_status == 200 and resp.body.get("success"), f"发码失败: {resp.body}"
        key = keys.login_code(phone)
        code = redis_cli.wait_key(key)
        ttl = redis_cli.ttl(key)
        assert 100 <= ttl <= 120, f"验证码 TTL 应 ≈ 120s，实际 {ttl}s"
        return code
    return _make


@pytest.fixture
def new_seckill_voucher(http, db, redis_cli, cfg):
    """动态造券（纪律一：用例自带造数，不吃存量——种子券 10 的窗口在容器首次导入时
    按 NOW() 求值，跑久了漂移到「已结束」，用例会变随机失败）。

    用法::

        with new_seckill_voucher(stock=50) as vid:
            ...

    - 时间注入统一 SQL `NOW() + 偏移`，与应用 System.currentTimeMillis() 同钟域；
    - 改窗口必须「UPDATE DB + DEL meta + DEL stock」三连（meta TTL 24h 不删不生效；
      建券即写 stock key，DEL 后由预热按 DB 回填——活动未开始才会回填，fail-closed）；
    - teardown 按依赖倒序：订单行 → seckill 券行 → 券行 → Redis 状态 key；
      清理失败 warn 不 raise（掩盖真正的用例失败比留残数据更糟）。
    """
    @contextmanager
    def _make(shop_id: int = 1, stock: int | None = None,
              begin_offset_s: int = -60, end_offset_s: int = 3600, title: str | None = None):
        if stock is None:
            stock = int(cfg.seckill.stock_default)
        now = datetime.now()
        voucher = {
            "shopId": shop_id,
            "title": title or f"pytest-{uuid.uuid4().hex[:8]}",
            "subTitle": "pytest 自动造数",
            "rules": "仅限到店使用",
            "payValue": 10000,
            "actualValue": 8000,
            "type": 1,
            "stock": stock,
            # Jackson 无 @JsonFormat → ISO 格式；只求建券成功，真实窗口随后 SQL 重设
            "beginTime": (now + timedelta(seconds=begin_offset_s)).strftime("%Y-%m-%dT%H:%M:%S"),
            "endTime": (now + timedelta(seconds=end_offset_s)).strftime("%Y-%m-%dT%H:%M:%S"),
        }
        resp = voucher_api.add_seckill_voucher(http, voucher)
        assert resp.http_status == 200 and resp.data, f"造券失败: {resp.body}"
        vid = int(resp.data)
        try:
            db.execute(
                "UPDATE tb_seckill_voucher SET stock = %s, "
                "begin_time = DATE_ADD(NOW(), INTERVAL %s SECOND), "
                "end_time = DATE_ADD(NOW(), INTERVAL %s SECOND) "
                "WHERE voucher_id = %s",
                (stock, begin_offset_s, end_offset_s, vid),
            )
            redis_cli.delete(keys.seckill_meta(vid), keys.seckill_stock(vid))
            yield vid
        finally:
            _teardown_voucher(db, redis_cli, vid)

    return _make


@pytest.fixture
def set_window(db, redis_cli):
    """重设活动窗口，并清掉必须随窗口一起失效的预热数据。"""
    def _set(voucher_id: int, *, begin_s: int, end_s: int) -> None:
        affected = db.execute(
            "UPDATE tb_seckill_voucher "
            "SET begin_time = DATE_ADD(NOW(), INTERVAL %s SECOND), "
            "end_time = DATE_ADD(NOW(), INTERVAL %s SECOND) "
            "WHERE voucher_id = %s",
            (begin_s, end_s, voucher_id),
        )
        assert affected == 1, f"秒杀券不存在，无法设置窗口: voucher_id={voucher_id}"
        redis_cli.delete(keys.seckill_meta(voucher_id), keys.seckill_stock(voucher_id))

    return _set


@pytest.fixture
def start_voucher(http, db, redis_cli, set_window, reset_rate_limit):
    """经真实预热路径把券从未开始推进到活动中，保留安全预热的库存。"""
    def _start(voucher_id: int, auth: AuthContext, *, begin_s: int = -1,
               end_s: int = 3600) -> None:
        set_window(voucher_id, begin_s=60, end_s=max(end_s, 120))
        reset_rate_limit(auth.user_id)
        warm_response = order_api.seckill(http, voucher_id, auth)
        assert warm_response.http_status == 200 and warm_response.code == 1007, (
            f"活动前预热失败: {warm_response.body}"
        )
        assert redis_cli.exists(keys.seckill_stock(voucher_id)), (
            f"活动前未生成库存 key: voucher_id={voucher_id}"
        )

        affected = db.execute(
            "UPDATE tb_seckill_voucher "
            "SET begin_time = DATE_ADD(NOW(), INTERVAL %s SECOND), "
            "end_time = DATE_ADD(NOW(), INTERVAL %s SECOND) "
            "WHERE voucher_id = %s",
            (begin_s, end_s, voucher_id),
        )
        assert affected == 1, f"秒杀券不存在，无法开始活动: voucher_id={voucher_id}"
        # 库存是活动开始前经真实入口预热所得。窗口推进后同步准备 meta，模拟正式
        # 开抢前的预热状态；若这里只 DEL meta，首波并发会争 tryLock(0)，未拿锁的
        # 请求会暂时读到 null 并被误判成“非秒杀券”，那测到的是 fixture 竞态。
        window = db.query_one(
            "SELECT CAST(UNIX_TIMESTAMP(begin_time) * 1000 AS UNSIGNED) AS begin_ms, "
            "CAST(UNIX_TIMESTAMP(end_time) * 1000 AS UNSIGNED) AS end_ms "
            "FROM tb_seckill_voucher WHERE voucher_id = %s",
            (voucher_id,),
        )
        meta_key = keys.seckill_meta(voucher_id)
        redis_cli.delete(meta_key)
        redis_cli.hset(meta_key, "begin", str(window["begin_ms"]))
        redis_cli.hset(meta_key, "end", str(window["end_ms"]))
        redis_cli.expire(meta_key, 24 * 60 * 60)
        reset_rate_limit(auth.user_id)

    return _start


@pytest.fixture
def end_voucher(db, redis_cli):
    """结束已开始的活动；保留库存账，只失效窗口 meta。"""
    def _end(voucher_id: int, *, seconds_ago: int = 1) -> None:
        affected = db.execute(
            "UPDATE tb_seckill_voucher "
            "SET end_time = DATE_SUB(NOW(), INTERVAL %s SECOND) "
            "WHERE voucher_id = %s",
            (seconds_ago, voucher_id),
        )
        assert affected == 1, f"秒杀券不存在，无法结束活动: voucher_id={voucher_id}"
        redis_cli.delete(keys.seckill_meta(voucher_id))

    return _end


@pytest.fixture
def protection_mode(redis_cli):
    """切换一人一券保护档位，并跨过应用内 3 秒快照；退出后恢复 FULL。"""
    @contextmanager
    def _use(mode: str):
        normalized = mode.strip().upper()
        redis_cli.set("seckill:test:protection", normalized)
        refresh_at = time.monotonic() + 3.2
        wait_until(lambda: time.monotonic() >= refresh_at, timeout=4, interval=0.05,
                   desc=f"等待保护档位切换为 {normalized}")
        try:
            yield
        finally:
            redis_cli.set("seckill:test:protection", "FULL")
            restore_at = time.monotonic() + 3.2
            wait_until(lambda: time.monotonic() >= restore_at, timeout=4, interval=0.05,
                       desc="等待保护档位恢复为 FULL")

    return _use


@pytest.fixture
def stop_service(cfg):
    """临时停止指定 Compose 依赖；无论用例结果如何都启动并等到 healthy。"""
    compose_root = Path(__file__).resolve().parents[2]
    allowed = {"redis", "rocketmq-broker"}

    def compose(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            ["docker", "compose", *args],
            cwd=compose_root,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )

    def container_id(service: str) -> str:
        result = compose("ps", "-q", service)
        assert result.returncode == 0, result.stderr
        return result.stdout.strip()

    def is_healthy(service: str) -> bool:
        cid = container_id(service)
        if not cid:
            return False
        result = subprocess.run(
            ["docker", "inspect", "--format", "{{.State.Health.Status}}", cid],
            capture_output=True,
            text=True,
            timeout=10,
            check=False,
        )
        return result.returncode == 0 and result.stdout.strip() == "healthy"

    def restart_local_app() -> None:
        # Redis 全停后容器 healthy 也不等于应用内的 Redisson/Lettuce 与 MQ
        # 消费线程已全部恢复；重启应用把故障恢复边界变成可验证的就绪点。
        # 只允许终止当前 8081 且命令行明确属于本工作区的进程树。
        workspace_pattern = str(compose_root).replace("'", "''")
        stop_script = (
            "$listener=Get-NetTCPConnection -LocalPort 8081 -State Listen "
            "-ErrorAction Stop|Select-Object -First 1;"
            "$app=Get-CimInstance Win32_Process -Filter "
            "\"ProcessId=$($listener.OwningProcess)\";"
            f"if($app.CommandLine -notlike '*{workspace_pattern}*'){{exit 23}};"
            "$parent=Get-CimInstance Win32_Process -Filter "
            "\"ProcessId=$($app.ParentProcessId)\";"
            "Stop-Process -Id $app.ProcessId -Force;"
            f"if($parent.CommandLine -like '*{workspace_pattern}*'){{"
            "Stop-Process -Id $parent.ProcessId -Force -ErrorAction SilentlyContinue}"
        )
        stopped = subprocess.run(
            ["powershell", "-NoProfile", "-Command", stop_script],
            capture_output=True,
            text=True,
            timeout=20,
            check=False,
        )
        assert stopped.returncode == 0, stopped.stderr

        env = os.environ.copy()
        env.update({
            "SPRING_DATASOURCE_URL": (
                f"jdbc:mysql://{cfg.mysql.host}:{cfg.mysql.port}/{cfg.mysql.database}"
                "?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"
            ),
            "SPRING_DATASOURCE_USERNAME": str(cfg.mysql.user),
            "SPRING_DATASOURCE_PASSWORD": str(cfg.mysql.password),
            "SPRING_REDIS_HOST": str(cfg.redis.host),
            "SPRING_REDIS_PORT": str(cfg.redis.port),
        })
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        maven = shutil.which("mvn.cmd") or shutil.which("mvn")
        assert maven, "PATH 中找不到 Maven，无法在 broker 恢复后重启应用"
        stdout_path = compose_root / "target" / "pytest-app.out.log"
        stderr_path = compose_root / "target" / "pytest-app.err.log"
        stdout_path.parent.mkdir(parents=True, exist_ok=True)
        with stdout_path.open("ab") as stdout_log, stderr_path.open("ab") as stderr_log:
            subprocess.Popen(
                [maven, "spring-boot:run"],
                cwd=compose_root,
                env=env,
                stdout=stdout_log,
                stderr=stderr_log,
                creationflags=creationflags,
            )

        def app_is_up() -> bool:
            try:
                with urllib.request.urlopen(
                        f"{cfg.base_url.rstrip('/')}/actuator/health", timeout=2) as response:
                    return response.status == 200 and b'"UP"' in response.read()
            except Exception:
                return False

        wait_until(app_is_up, timeout=60, interval=0.5, desc="等待应用重启")

    @contextmanager
    def _stop(service: str):
        assert service in allowed, f"不允许停止未列入白名单的服务: {service}"
        stopped = compose("stop", service)
        assert stopped.returncode == 0, stopped.stderr
        wait_until(lambda: not container_id(service), timeout=15, interval=0.2,
                   desc=f"等待 {service} 停止")
        try:
            yield
        finally:
            started = compose("start", service)
            assert started.returncode == 0, started.stderr
            wait_until(lambda: is_healthy(service), timeout=45, interval=1,
                       desc=f"等待 {service} 恢复健康")
            if service == "redis":
                restart_local_app()

    return _stop


@pytest.fixture
def new_shop(http, db, redis_cli):
    """动态创建独立商铺，隔离每条缓存用例的 L1/L2 与指标窗口。"""
    @contextmanager
    def _make(name: str | None = None):
        shop = {
            "name": name or f"pytest-shop-{uuid.uuid4().hex[:8]}",
            "typeId": 1,
            "images": "pytest.jpg",
            "area": "pytest-area",
            "address": "pytest-address",
            "x": 121.0,
            "y": 31.0,
            "avgPrice": 20,
            "sold": 0,
            "comments": 0,
            "score": 50,
            "openHours": "08:00-22:00",
        }
        resp = http.post("/shop", json=shop)
        assert resp.http_status == 200 and resp.data, f"创建测试商铺失败: {resp.body}"
        shop_id = int(resp.data)
        try:
            yield shop_id
        finally:
            redis_cli.delete(keys.cache_shop(shop_id), keys.lock_shop(shop_id))
            db.execute("DELETE FROM tb_shop WHERE id = %s", (shop_id,))

    return _make


def _teardown_voucher(db: DbHelper, redis_cli: RedisHelper, vid: int) -> None:
    """造数与清理严格对称（纪律二）；唯一索引 uk_user_voucher 会让残单把重跑打成 ORDER_REPEAT。"""
    order_ids: List[int] = []
    try:
        rows = db.query("SELECT id FROM tb_voucher_order WHERE voucher_id = %s", (vid,))
        order_ids = [row["id"] for row in rows]
    except Exception as exc:  # noqa: BLE001 —— 清理失败只 warn
        log.warning("券 %s 清理：收集订单号失败: %s", vid, exc)
    if order_ids:
        try:
            redis_cli.delete(*[keys.seckill_queue(oid) for oid in order_ids])
        except Exception as exc:  # noqa: BLE001
            log.warning("券 %s 清理：删排队状态 key 失败: %s", vid, exc)
    for sql in (
        "DELETE FROM tb_voucher_order WHERE voucher_id = %s",
        "DELETE FROM tb_seckill_voucher WHERE voucher_id = %s",
        "DELETE FROM tb_voucher WHERE id = %s",
    ):
        try:
            db.execute(sql, (vid,))
        except Exception as exc:  # noqa: BLE001
            log.warning("券 %s 清理：%s 失败: %s", vid, sql.split()[2], exc)
    try:
        redis_cli.clear_seckill_state(vid)
    except Exception as exc:  # noqa: BLE001
        log.warning("券 %s 清理：Redis 状态 key 失败: %s", vid, exc)
