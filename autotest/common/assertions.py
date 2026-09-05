"""四类断言助手（框架结构票 §4）：统一入口，先解 HTTP 层，再解业务层。

三类通用（业务码 / DB / Redis）+ 一类项目特色（指标，见 common/metrics.py）。

「common 层无业务语义」的例外说明：本文件下半部分的 assert_order_exists /
assert_stock / assert_in_seckill_order_set 等业务封装，是框架结构票 §4.2/§4.3
明文要求「直接写进 assertions.py」的——它们是断言助手的领域快捷方式，不是业务逻辑；
三链路通用（登录/缓存/抢券都要查订单查库存），放 common 是规格决策。

四种"非标准 body"的处置（源码核实，写死在助手里的世界观）：
| 场景        | HTTP | body                              | 断言方式                     |
|-------------|------|-----------------------------------|------------------------------|
| 业务失败    | 200  | {success:false, code:1003, ...}   | 断言 code == 1003            |
| 未登录      | 401  | 空（拦截器只 setStatus）           | 只断言 http_status，不碰 body |
| 被限流      | 429  | {success:false, code:null, ...}   | 断言 http_status + msg_contains |
| 系统故障    | 503  | {success:false, code:5002, ...}   | 断言 code；trace_id 已挂 allure |
"""
from __future__ import annotations

from typing import Any, Callable, List, Optional

from . import keys
from .db import DbHelper
from .redis_helper import RedisHelper
from .wait import wait_until


# ============================================================ 业务码断言 ====
def assert_result(resp, *, http_status: int = 200, success: Optional[bool] = None,
                  code: Optional[int] = None, msg_contains: Optional[str] = None,
                  data_check: Optional[Callable[[Any], bool]] = None) -> Any:
    """HTTP 层 + 业务层两级断言，返回 resp.data（成功路径常用）。

    - code 传 None 表示"不校验"（如 429 的 body 里 code 本来就是 null）；
    - 401 空 body 只许断言 http_status，传业务层参数视为用例写错；
    - data_check 拿到的是 body["data"]，返回 False 即失败。
    """
    assert resp.http_status == http_status, (
        f"HTTP 状态码 {resp.http_status} != {http_status}: {resp.error_msg or resp.body}"
    )
    has_business_assert = any(x is not None for x in (success, code, msg_contains, data_check))
    if resp.body is None:
        if has_business_assert:
            raise AssertionError(
                f"http_status={resp.http_status} 的响应 body 为空，不携带业务字段——"
                f"只能断言 http_status（401 场景），传业务层参数是用例写错了"
            )
        return None
    if success is not None:
        assert resp.body.get("success") is success, f"success={resp.body.get('success')} != {success}"
    if code is not None:
        assert resp.code == code, f"code={resp.code} != {code}（msg={resp.error_msg}）"
    if msg_contains is not None:
        assert msg_contains in (resp.error_msg or ""), (
            f"errorMsg={resp.error_msg!r} 不包含 {msg_contains!r}"
        )
    if data_check is not None:
        assert data_check(resp.data), f"data 断言失败: {resp.data}"
    return resp.data


# ============================================================ DB 断言 ====
def _single_value(rows: List[dict]) -> Any:
    if len(rows) != 1 or len(rows[0]) != 1:
        raise ValueError(f"期望单行单列，实际 {len(rows)} 行 {list(rows[0]) if rows else '-'} 列")
    return next(iter(rows[0].values()))


def assert_db(db: DbHelper, sql: str, params: Optional[dict | tuple] = None, *,
              expected: Any = None, check: Optional[Callable[[List[dict]], bool]] = None,
              timeout: float = 0) -> List[dict]:
    """DB 断言。expected = 单行单列精确等值；check = 自定义谓词（多行场景）。

    timeout > 0 时 eventually 轮询（异步落库唯一正解，内部 wait_until，禁 sleep），
    轮询到超时后仍失败才抛 AssertionError。
    """
    def _ok(rows: List[dict]) -> bool:
        try:
            if check is not None:
                return bool(check(rows))
            if expected is not None:
                return _single_value(rows) == expected
        except (ValueError, IndexError):
            return False
        return True

    if timeout > 0:
        wait_until(lambda: _ok(db.query(sql, params)), timeout=timeout, desc=f"SQL: {sql}")
    rows = db.query(sql, params)
    try:
        assert _ok(rows), f"DB 断言失败: {sql} params={params} 期望={expected!r} 实际={rows!r}"
    except (ValueError, IndexError) as exc:
        raise AssertionError(f"DB 断言失败: {sql} params={params} 结果形状不对: {rows!r}") from exc
    return rows


def assert_order_exists(db: DbHelper, user_id: int, voucher_id: int, *, timeout: float = 5.0) -> dict:
    """订单行存在（异步落库，默认 eventually 5s），返回该行。"""
    sql = "SELECT * FROM tb_voucher_order WHERE user_id = %(u)s AND voucher_id = %(v)s"
    rows = assert_db(db, sql, {"u": user_id, "v": voucher_id},
                     check=lambda rs: len(rs) >= 1, timeout=timeout)
    return rows[0]


def assert_order_count(db: DbHelper, voucher_id: int, expected: int, *, timeout: float = 5.0) -> None:
    """某券订单总数（对账主判据的 COUNT 侧；不筛 used——语义见订单状态机票）。"""
    sql = "SELECT COUNT(*) AS c FROM tb_voucher_order WHERE voucher_id = %(v)s"
    assert_db(db, sql, {"v": voucher_id}, expected=expected, timeout=timeout)


def assert_stock(db: DbHelper, voucher_id: int, expected: int, *, timeout: float = 5.0) -> None:
    """DB 库存账（对账主判据的 stock 侧：stock == initial − COUNT，精确等式）。"""
    sql = "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = %(v)s"
    assert_db(db, sql, {"v": voucher_id}, expected=expected, timeout=timeout)


# ============================================================ Redis 断言 ====
def assert_redis(r: RedisHelper, key: str, *, value: Optional[str] = None,
                 check: Optional[Callable[[Optional[str]], bool]] = None,
                 ttl_range: Optional[tuple] = None, absent: bool = False,
                 timeout: float = 0) -> None:
    """Redis 侧断言。value = GET 精确等值；ttl_range = (lo, hi) 闭区间秒；
    absent = 断言 key 不存在。timeout > 0 时 eventually 轮询。
    """
    expected_value = str(value) if value is not None and not isinstance(value, str) else value

    def _ok() -> bool:
        exists = r.exists(key)
        if absent:
            return not exists
        if not exists:
            return value is None and check is None and ttl_range is None
        current = r.get(key)
        if expected_value is not None and current != expected_value:
            return False
        if check is not None and not check(current):
            return False
        if ttl_range is not None:
            lo, hi = ttl_range
            if not (lo <= r.ttl(key) <= hi):
                return False
        return True

    if timeout > 0:
        wait_until(_ok, timeout=timeout, desc=f"Redis key {key}")
    assert _ok(), (
        f"Redis 断言失败: key={key} value={expected_value!r} ttl_range={ttl_range} "
        f"absent={absent} 实际: exists={r.exists(key)} value={r.get(key)!r} ttl={r.ttl(key)}"
    )


def assert_stock_key(r: RedisHelper, voucher_id: int, expected: int, *, timeout: float = 5.0) -> None:
    """Redis 库存 key（seckill:stock:{vid}）。注意 W8：活动中 key 缺失 = fail-closed，
    存在性断言用 absent=True。"""
    assert_redis(r, keys.seckill_stock(voucher_id), value=str(expected), timeout=timeout)


def assert_in_seckill_order_set(r: RedisHelper, voucher_id: int, user_id: int, *,
                                member: bool = True) -> None:
    """成功用户集合（seckill:order:{vid}）成员断言。"""
    key = keys.seckill_order(voucher_id)
    actual = r.sismember(key, str(user_id))
    assert actual is member, f"sismember {keys.SECKILL_ORDER}{voucher_id} {user_id} = {actual}，期望 {member}"


def assert_cache_shop(r: RedisHelper, shop_id: int, *, present: bool = True) -> None:
    """缓存 key 存在性（cache:shop:{sid}；evict 后应 absent）。"""
    assert r.exists(keys.cache_shop(shop_id)) is present


def assert_login_token(r: RedisHelper, token: str, *, present: bool = True) -> None:
    """登录态 key 存在性（login:token:{token}；DEL 注入会话失效后应 absent）。"""
    assert r.exists(keys.login_token(token)) is present
