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
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta
from typing import List

import pytest

from api import user_api, voucher_api
from common import keys
from common.client import ApiClient, AuthContext, AuthedClient
from common.config import Config
from common.db import DbHelper
from common.metrics import MetricsHelper
from common.phone_pool import PhonePool
from common.redis_helper import RedisHelper

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
