"""两级缓存链路：HTTP 是主断言，Redis/MySQL 用于状态注入与观测。"""
import json
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from threading import Thread

import pytest
import redis as redis_lib

from api import shop_api
from common import keys
from common.wait import wait_until


def _assert_shop_ok(resp, shop_id: int) -> dict:
    assert resp.http_status == 200, resp.body
    assert resp.body and resp.body.get("success") is True, resp.body
    assert resp.data["id"] == shop_id
    return resp.data


def _put_cached_shop(redis_cli, shop: dict, *, expired: bool = False) -> None:
    expire_at = datetime.now() + (timedelta(seconds=-1) if expired else timedelta(minutes=30))
    redis_cli.set(
        keys.cache_shop(int(shop["id"])),
        json.dumps({"data": shop, "expireTime": expire_at.isoformat(timespec="seconds")}, ensure_ascii=False),
        ex=5400,
    )


def _cached_data(redis_cli, shop_id: int) -> dict:
    raw = redis_cli.get(keys.cache_shop(shop_id))
    assert raw is not None
    return json.loads(raw)


def _local_datetime(value) -> datetime:
    """兼容当前 Jackson JavaTimeModule 的数组格式及 ISO 字符串格式。"""
    if isinstance(value, list):
        return datetime(*value)
    if isinstance(value, (int, float)):
        return datetime.fromtimestamp(value / 1000)
    return datetime.fromisoformat(value)


def test_l1_hit_after_cold_fill(http, redis_cli, metrics, new_shop):
    """A1：首次请求填充两级缓存，第二次请求从本实例 L1 返回。"""
    with new_shop() as shop_id:
        redis_cli.delete(keys.cache_shop(shop_id))
        first = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        assert redis_cli.exists(keys.cache_shop(shop_id))

        with metrics.snapshot() as snap:
            second = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)

        assert second["name"] == first["name"]
        snap.delta_eq("hmdp.cache.hit", {"level": "l1"}, 1)


@pytest.mark.slow
def test_l1_expiry_falls_back_to_l2(http, metrics, new_shop):
    """A2：L1 的 30 秒 TTL 到期后回落到仍有效的 L2。"""
    with new_shop() as shop_id:
        _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        time.sleep(31)
        with metrics.snapshot() as snap:
            _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        snap.assert_no_increase("hmdp.cache.hit", {"level": "l1"})
        snap.delta_ge("hmdp.cache.hit", {"level": "l2"}, 1)


def test_cold_read_rebuilds_l2_with_physical_ttl(http, redis_cli, metrics, new_shop):
    """A3：冷读回源后将正确的数据库数据写入 L2。"""
    with new_shop() as shop_id:
        redis_cli.delete(keys.cache_shop(shop_id))
        with metrics.snapshot() as snap:
            data = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        cached = _cached_data(redis_cli, shop_id)
        assert cached["data"]["name"] == data["name"]
        assert 0 < redis_cli.ttl(keys.cache_shop(shop_id)) <= 5400
        snap.delta_ge("hmdp.cache.hit", {"level": "db"}, 1)


def test_l2_physical_ttl_is_bounded_by_ninety_minutes(http, redis_cli, new_shop):
    """B3：物理 TTL 保险丝存在，最迟 90 分钟自动收敛。"""
    with new_shop() as shop_id:
        _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        assert 0 < redis_cli.ttl(keys.cache_shop(shop_id)) <= 5400


@pytest.mark.slow
def test_logical_expiry_returns_stale_once_then_next_read_sees_rebuilt_value(
        http, db, redis_cli, metrics, new_shop):
    """A4：逻辑过期值只服务当前请求，不进入 L1 阻挡重建后的新值。"""
    with new_shop("old-name") as shop_id:
        fresh = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        old = dict(fresh)
        old["name"] = "old-name"
        db.execute("UPDATE tb_shop SET name = %s WHERE id = %s", ("new-name", shop_id))
        time.sleep(31)  # 清掉刚才为取完整 JSON 而填入的 L1
        _put_cached_shop(redis_cli, old, expired=True)
        with metrics.snapshot() as snap:
            first = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
            assert first["name"] == "old-name"
            wait_until(lambda: _cached_data(redis_cli, shop_id)["data"]["name"] == "new-name",
                       timeout=10, desc="等待逻辑过期缓存异步重建")
        snap.delta_ge("hmdp.cache.rebuild", {"result": "ok"}, 1)
        assert _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)["name"] == "new-name"


def test_update_evicts_both_levels_and_next_read_is_fresh(http, redis_cli, metrics, new_shop):
    """B1：事务提交后同步清两级缓存，下一次读取立即看到新值。"""
    with new_shop("before-update") as shop_id:
        _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        with metrics.snapshot() as snap:
            resp = shop_api.update_shop(http, {"id": shop_id, "name": "after-update"})
        assert resp.http_status == 200 and resp.body.get("success") is True
        assert not redis_cli.exists(keys.cache_shop(shop_id))
        snap.delta_eq("hmdp.cache.evict", {"result": "ok"}, 1)
        assert _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)["name"] == "after-update"


@pytest.mark.slow
def test_remote_instance_evict_equivalent_exposes_bounded_l1_staleness(http, db, redis_cli, new_shop):
    """B2：别的实例更新 DB 并删 L2 后，本实例 L1 最多脏读 30 秒。"""
    with new_shop("old-name") as shop_id:
        _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        db.execute("UPDATE tb_shop SET name = %s WHERE id = %s", ("new-name", shop_id))
        redis_cli.delete(keys.cache_shop(shop_id))
        assert _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)["name"] == "old-name"
        time.sleep(31)
        assert _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)["name"] == "new-name"


def test_missing_shop_uses_l2_null_marker(http, redis_cli, metrics):
    """C1：相同不存在 ID 只回源一次，随后由空值标记拦截穿透。"""
    shop_id = 999_999_991
    key = keys.cache_shop(shop_id)
    redis_cli.delete(key)
    try:
        first = shop_api.query_by_id(http, shop_id)
        assert first.http_status == 200 and first.body.get("success") is False
        assert redis_cli.get(key) == ""
        assert 0 < redis_cli.ttl(key) <= 240
        with metrics.snapshot() as snap:
            second = shop_api.query_by_id(http, shop_id)
        assert second.http_status == 200 and second.body.get("success") is False
        snap.assert_no_increase("hmdp.cache.hit", {"level": "db"})
        snap.delta_ge("hmdp.cache.hit", {"level": "l2"}, 1)
    finally:
        redis_cli.delete(key)


@pytest.mark.isolate
def test_distinct_missing_ids_still_each_reach_db(http, redis_cli, metrics):
    """C2：空值只防重复 ID，100 个不同无效 ID 仍会各自穿透一次。"""
    ids = range(999_900_001, 999_900_101)
    cache_keys = [keys.cache_shop(shop_id) for shop_id in ids]
    redis_cli.delete(*cache_keys)
    try:
        with metrics.snapshot() as snap:
            responses = [shop_api.query_by_id(http, shop_id) for shop_id in ids]
        assert all(r.http_status == 200 and r.body.get("success") is False for r in responses)
        assert all(redis_cli.get(key) == "" for key in cache_keys)
        snap.delta_eq("hmdp.cache.hit", {"level": "db"}, 100)
    finally:
        redis_cli.delete(*cache_keys)


@pytest.mark.serial
def test_concurrent_cold_reads_share_rebuilt_l2(http, redis_cli, metrics, new_shop):
    """C3：50 个并发冷读均正确，且等待者能命中首个请求重建的 L2。"""
    with new_shop() as shop_id:
        redis_cli.delete(keys.cache_shop(shop_id))
        with metrics.snapshot() as snap, ThreadPoolExecutor(max_workers=50) as pool:
            responses = list(pool.map(lambda _: shop_api.query_by_id(http, shop_id), range(50)))
        assert all(_assert_shop_ok(resp, shop_id) for resp in responses)
        snap.delta_ge("hmdp.cache.hit", {"level": "l2"}, 1)


@pytest.mark.serial
def test_mutex_wait_is_bounded_and_does_not_write_cache(http, redis_cli, new_shop):
    """C4：锁被占用时等待约 1 秒便自行回源，且不污染 L2。"""
    with new_shop() as shop_id:
        redis_cli.delete(keys.cache_shop(shop_id))
        redis_cli.fake_redisson_lock(keys.lock_shop(shop_id))
        try:
            started = time.monotonic()
            _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
            elapsed = time.monotonic() - started
            assert 1 <= elapsed <= 3, f"有界等待耗时应在 [1,3]s，实际 {elapsed:.3f}s"
            assert not redis_cli.exists(keys.cache_shop(shop_id))
        finally:
            redis_cli.delete(keys.lock_shop(shop_id))


def test_logical_ttl_jitter_is_present(http, redis_cli, new_shop):
    """C5：逻辑 TTL 均落在 30~36 分钟，且样本中确有离散。"""
    contexts = [new_shop() for _ in range(10)]
    entered = []
    try:
        ids = [entered.append(ctx.__enter__()) or entered[-1] for ctx in contexts]
        before = datetime.now()
        for shop_id in ids:
            _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        expiries = [_local_datetime(_cached_data(redis_cli, shop_id)["expireTime"]) for shop_id in ids]
        assert all(before + timedelta(minutes=30) <= expiry <= datetime.now() + timedelta(minutes=36)
                   for expiry in expiries)
        assert len(set(expiries)) >= 2
    finally:
        for ctx in reversed(contexts[:len(entered)]):
            ctx.__exit__(None, None, None)


@pytest.mark.isolate
@pytest.mark.serial
def test_expired_value_rebuild_is_deduplicated(http, redis_cli, metrics, new_shop):
    """C6：并发读取同一逻辑过期值只触发一次异步重建。"""
    with new_shop() as shop_id:
        shop = _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
        time.sleep(31)
        _put_cached_shop(redis_cli, shop, expired=True)
        with metrics.snapshot() as snap, ThreadPoolExecutor(max_workers=10) as pool:
            responses = list(pool.map(lambda _: shop_api.query_by_id(http, shop_id), range(10)))
        assert all(_assert_shop_ok(resp, shop_id) for resp in responses)
        wait_until(lambda: snap.delta("hmdp.cache.rebuild", {"result": "ok"}) >= 1,
                   timeout=10, desc="等待唯一异步重建")
        snap.delta_eq("hmdp.cache.rebuild", {"result": "ok"}, 1)


@pytest.mark.chaos
@pytest.mark.serial
def test_redis_stall_falls_back_and_retries(http, redis_cli, metrics, new_shop):
    """D1：Redis 卡死时读请求降级到 DB，且幂等读重试层真实生效。"""
    with new_shop() as shop_id:
        redis_cli.delete(keys.cache_shop(shop_id))
        connection = redis_cli.raw.connection_pool.connection_kwargs

        def stall_redis():
            injector = redis_lib.Redis(
                host=connection["host"], port=connection["port"], db=connection.get("db", 0),
                socket_timeout=12,
            )
            try:
                injector.execute_command("DEBUG", "SLEEP", "8")
            finally:
                injector.close()

        sleeper = Thread(target=stall_redis, daemon=True)
        with metrics.snapshot() as snap:
            sleeper.start()
            time.sleep(0.1)  # DEBUG 命令需先进入 Redis 事件循环，非业务等待
            with ThreadPoolExecutor(max_workers=10) as pool:
                responses = list(pool.map(lambda _: shop_api.query_by_id(http, shop_id), range(10)))
            # 前 10 个并发调用提供 minimum-number-of-calls 样本；追加调用应被 open 快速拒绝。
            responses.append(shop_api.query_by_id(http, shop_id))
            sleeper.join(timeout=12)
        assert all(_assert_shop_ok(resp, shop_id) for resp in responses)
        snap.delta_ge("hmdp.resilience.fallback", {"breaker": "redisBreaker", "kind": "error"}, 1)
        snap.delta_ge("hmdp.resilience.fallback", {"breaker": "redisBreaker", "kind": "not_permitted"}, 1)
        snap.delta_ge("hmdp.resilience.retry", {"retry": "cacheQueryRetry", "kind": "retry"}, 1)


@pytest.mark.slow
@pytest.mark.serial
def test_circuit_breaker_recovers_after_half_open_probes(http, metrics, new_shop):
    """D2：打开的 Redis 熔断器自动转半开，并经 3 次健康探测恢复关闭。"""
    wait_until(lambda: metrics.value("resilience4j_circuitbreaker_state",
                                    {"name": "redisBreaker", "state": "half_open"}) == 1,
               timeout=20, desc="等待 redisBreaker 半开")
    with new_shop() as shop_id:
        for _ in range(3):
            _assert_shop_ok(shop_api.query_by_id(http, shop_id), shop_id)
    wait_until(lambda: metrics.value("resilience4j_circuitbreaker_state",
                                    {"name": "redisBreaker", "state": "closed"}) == 1,
               timeout=5, desc="等待 redisBreaker 关闭")
