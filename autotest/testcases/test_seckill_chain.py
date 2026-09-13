"""限时领券链路：HTTP 为行为接缝，DB/Redis 只负责造数与独立对账。"""

from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
import time

import pytest

from api import order_api
from common import keys
from common.assertions import assert_order_exists, assert_result
from common.wait import wait_until


def _voucher_ledger(db, voucher_id: int) -> dict:
    return db.query_one(
        "SELECT sv.stock AS db_stock, COUNT(vo.id) AS orders, "
        "COUNT(DISTINCT vo.user_id) AS users "
        "FROM tb_seckill_voucher sv "
        "LEFT JOIN tb_voucher_order vo ON vo.voucher_id = sv.voucher_id "
        "WHERE sv.voucher_id = %s GROUP BY sv.voucher_id, sv.stock",
        (voucher_id,),
    )


def _assert_four_way_reconciliation(db, redis_cli, voucher_id: int,
                                    initial_stock: int, expected_orders: int,
                                    *, timeout: float = 15) -> None:
    ledger = wait_until(
        lambda: (
            row
            if (row := _voucher_ledger(db, voucher_id))
            and int(row["orders"]) == expected_orders
            else None
        ),
        timeout=timeout,
        interval=0.1,
        desc="等待领取记录落库",
    )
    orders = int(ledger["orders"])
    db_stock = int(ledger["db_stock"])
    assert db_stock == initial_stock - orders
    assert int(ledger["users"]) == orders
    assert int(redis_cli.get(keys.seckill_stock(voucher_id))) == db_stock
    assert redis_cli.scard(keys.seckill_order(voucher_id)) == orders


def test_login_factory_recovers_when_only_send_cooldown_remains(
        login, phone_pool, redis_cli):
    """测试基础设施：上次进程消费验证码后，残留冷却键不能阻塞下一次登录。"""
    phone = phone_pool.take(1)[0]
    redis_cli.delete(keys.login_code(phone), keys.login_code_cooldown(phone))
    redis_cli.set(keys.login_code_cooldown(phone), "1", ex=60)
    try:
        context = login(phone)
        assert context.user_id is not None
        assert context.token
    finally:
        redis_cli.delete(keys.login_code(phone), keys.login_code_cooldown(phone))


def test_w1_claim_before_begin_is_rejected_and_stock_is_warmed(
        http, user, reset_rate_limit, new_seckill_voucher, set_window, redis_cli):
    """W1：活动未开始时拒绝领券，但会提前预热 Redis 库存。"""
    with new_seckill_voucher(stock=3) as voucher_id:
        set_window(voucher_id, begin_s=60, end_s=3600)
        reset_rate_limit(user.user_id)

        response = order_api.seckill(http, voucher_id, user)

        assert_result(response, success=False, code=1007)
        assert redis_cli.get(keys.seckill_stock(voucher_id)) == "3"


def test_w2_claim_one_second_before_begin_is_rejected(
        http, user, reset_rate_limit, new_seckill_voucher, set_window):
    """W2：开抢前 1 秒仍属于未开始窗口。"""
    with new_seckill_voucher(stock=1) as voucher_id:
        set_window(voucher_id, begin_s=1, end_s=3600)
        reset_rate_limit(user.user_id)

        assert_result(order_api.seckill(http, voucher_id, user), success=False, code=1007)


def test_w3_user_can_claim_after_begin(
        http, user, new_seckill_voucher, start_voucher):
    """W3：活动开始后且库存充足时可以领取。"""
    with new_seckill_voucher(stock=1) as voucher_id:
        start_voucher(voucher_id, user)

        response = order_api.seckill(http, voucher_id, user)

        assert_result(
            response,
            success=True,
            data_check=lambda order_id: isinstance(order_id, int) and order_id > 0,
        )


def test_w4_claim_one_second_before_end_succeeds(
        http, user, new_seckill_voucher, start_voucher):
    """W4：结束前 1 秒仍在 [begin, end) 活动窗口内。"""
    with new_seckill_voucher(stock=1) as voucher_id:
        start_voucher(voucher_id, user, end_s=1)

        assert_result(order_api.seckill(http, voucher_id, user), success=True)


def test_w8_missing_stock_during_active_window_fails_closed(
        http, user, db, redis_cli, new_seckill_voucher, start_voucher):
    """W8：活动中 Redis 库存丢失时宁可少发，也不从 DB 回填。"""
    with new_seckill_voucher(stock=2) as voucher_id:
        start_voucher(voucher_id, user)
        redis_cli.delete(keys.seckill_stock(voucher_id))

        response = order_api.seckill(http, voucher_id, user)

        assert_result(response, success=False, code=1003)
        assert not redis_cli.exists(keys.seckill_stock(voucher_id))
        assert db.query_value(
            "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = %s",
            (voucher_id,),
        ) == 0


def test_c5_successful_claim_eventually_becomes_queryable(
        http, user, new_seckill_voucher, start_voucher, reset_rate_limit, db):
    """C5：提交成功后，异步结果查询最终返回 SUCCESS，DB 领取记录可见。"""
    with new_seckill_voucher(stock=1) as voucher_id:
        start_voucher(voucher_id, user)
        order_id = assert_result(order_api.seckill(http, voucher_id, user), success=True)
        reset_rate_limit(user.user_id)

        result = wait_until(
            lambda: (
                response
                if (response := order_api.seckill_result(http, order_id, user)).data
                and response.data.get("status") == "SUCCESS"
                else None
            ),
            timeout=10,
            interval=0.1,
            desc="等待领取结果进入 SUCCESS",
        )
        assert_result(result, success=True)
        order = assert_order_exists(db, user.user_id, voucher_id, timeout=5)
        assert int(order["id"]) == order_id


@pytest.mark.serial
def test_c2_hundred_users_compete_for_exactly_fifty_claims(
        http, user_pool, db, redis_cli, new_seckill_voucher, start_voucher):
    """C2：满员竞速后成功数精确等于库存，且四方账本一致。"""
    users = user_pool(100)
    with new_seckill_voucher(stock=50) as voucher_id:
        start_voucher(voucher_id, users[0])
        gate = Barrier(len(users))

        def claim(auth):
            gate.wait()
            return order_api.seckill(http, voucher_id, auth)

        with ThreadPoolExecutor(max_workers=len(users)) as pool:
            responses = list(pool.map(claim, users))

        successes = [response for response in responses if response.body.get("success") is True]
        stock_out = [response for response in responses if response.code == 1003]
        assert len(successes) == 50
        assert len(stock_out) == 50
        assert len(successes) + len(stock_out) == len(responses)
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 50, 50)


@pytest.mark.serial
def test_c1_hundred_users_all_claim_when_stock_is_sufficient(
        http, user_pool, db, redis_cli, new_seckill_voucher, start_voucher):
    """C1：库存充足时 100 位并发用户全部成功，四方账本无少发。"""
    users = user_pool(100)
    with new_seckill_voucher(stock=300) as voucher_id:
        start_voucher(voucher_id, users[0])
        gate = Barrier(len(users))

        def claim(auth):
            gate.wait()
            return order_api.seckill(http, voucher_id, auth)

        with ThreadPoolExecutor(max_workers=len(users)) as pool:
            responses = list(pool.map(claim, users))

        assert all(response.body.get("success") is True for response in responses), [
            (response.http_status, response.code, response.error_msg)
            for response in responses if response.body.get("success") is not True
        ]
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 300, 100)


@pytest.mark.serial
def test_c3_same_user_can_only_claim_once_under_concurrent_and_sequential_retries(
        http, user, db, redis_cli, reset_rate_limit, new_seckill_voucher, start_voucher):
    """C3：同一用户并发重入与顺序重试都只能形成一条领取记录。"""
    with new_seckill_voucher(stock=2) as voucher_id:
        start_voucher(voucher_id, user)
        gate = Barrier(5)

        def claim_once(_):
            gate.wait()
            return order_api.seckill(http, voucher_id, user)

        with ThreadPoolExecutor(max_workers=5) as pool:
            concurrent_responses = list(pool.map(claim_once, range(5)))

        assert sum(response.body.get("success") is True for response in concurrent_responses) == 1
        assert sum(response.code == 1004 for response in concurrent_responses) == 4

        sequential_responses = []
        for _ in range(10):
            reset_rate_limit(user.user_id)
            sequential_responses.append(order_api.seckill(http, voucher_id, user))
        assert all(response.code == 1004 for response in sequential_responses)
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 2, 1)


@pytest.mark.serial
def test_c4_early_mode_exposes_redis_over_decrement_while_db_uniqueness_holds(
        http, user_pool, db, redis_cli, new_seckill_voucher, start_voucher,
        protection_mode):
    """C4：EARLY 无入口去重会多扣 Redis，但 DB 唯一索引仍保证一人一券。"""
    users = user_pool(50)
    with new_seckill_voucher(stock=20) as voucher_id:
        start_voucher(voucher_id, users[0])
        with protection_mode("EARLY"):
            attempts = [auth for auth in users for _ in range(2)]
            gate = Barrier(len(attempts))

            def claim(auth):
                gate.wait()
                return order_api.seckill(http, voucher_id, auth)

            with ThreadPoolExecutor(max_workers=len(attempts)) as pool:
                responses = list(pool.map(claim, attempts))

        accepted = [response for response in responses
                    if response.body.get("success") is True]
        assert len(accepted) == 20
        ledger = wait_until(
            lambda: (
                row
                if (row := _voucher_ledger(db, voucher_id))
                and int(row["orders"]) > 0
                and int(row["orders"]) < len(accepted)
                else None
            ),
            timeout=15,
            interval=0.1,
            desc="等待 EARLY 重复消息由 DB 唯一索引收敛",
        )
        orders = int(ledger["orders"])
        assert int(ledger["users"]) == orders
        assert int(ledger["db_stock"]) == 20 - orders
        assert int(redis_cli.get(keys.seckill_stock(voucher_id))) == 0
        assert int(ledger["db_stock"]) > 0
        assert redis_cli.scard(keys.seckill_order(voucher_id)) == 0


@pytest.mark.slow
@pytest.mark.serial
@pytest.mark.isolate
def test_c6_reconcile_supplements_missing_claim_with_original_order_id(
        user, db, redis_cli, metrics, new_seckill_voucher, end_voucher):
    """C6：对账从 Redis 差集补领，并复用入口已认领的原始编号。"""
    with new_seckill_voucher(stock=3) as voucher_id:
        order_id = 9_000_000_000_000_000_000 + voucher_id
        redis_cli.set(keys.seckill_stock(voucher_id), "2")
        redis_cli.sadd(keys.seckill_order(voucher_id), str(user.user_id))
        redis_cli.hset(keys.seckill_claim(voucher_id), str(user.user_id), str(order_id))
        end_voucher(voucher_id, seconds_ago=9 * 60)

        with metrics.snapshot() as snapshot:
            order = wait_until(
                lambda: db.query_one(
                    "SELECT id, user_id FROM tb_voucher_order "
                    "WHERE voucher_id = %s AND user_id = %s",
                    (voucher_id, user.user_id),
                ),
                timeout=90,
                interval=0.5,
                desc="等待对账任务补领",
            )

        assert int(order["id"]) == order_id
        snapshot.delta_eq("hmdp.reconcile.supplement", {"result": "ok"}, 1)
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 3, 1)


@pytest.mark.slow
@pytest.mark.serial
@pytest.mark.isolate
def test_c7_reconcile_recomputes_finished_voucher_stock_from_db_ledger(
        http, user, db, redis_cli, metrics, new_seckill_voucher, start_voucher,
        end_voucher):
    """C7/W6：活动结束后，DB 账本驱动 DB 与 Redis 库存重新收敛。"""
    with new_seckill_voucher(stock=3) as voucher_id:
        start_voucher(voucher_id, user)
        assert_result(order_api.seckill(http, voucher_id, user), success=True)
        assert_order_exists(db, user.user_id, voucher_id, timeout=10)

        db.execute(
            "UPDATE tb_seckill_voucher SET stock = 1 WHERE voucher_id = %s",
            (voucher_id,),
        )
        redis_cli.set(keys.seckill_stock(voucher_id), "0")
        end_voucher(voucher_id, seconds_ago=9 * 60)

        with metrics.snapshot() as snapshot:
            wait_until(
                lambda: (
                    row
                    if (row := _voucher_ledger(db, voucher_id))
                    and int(row["db_stock"]) == 2
                    and redis_cli.get(keys.seckill_stock(voucher_id)) == "2"
                    else None
                ),
                timeout=90,
                interval=0.5,
                desc="等待结束活动库存对账收敛",
            )

        snapshot.delta_eq("hmdp.reconcile.restock", {"result": "adjusted"}, 1)
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 3, 1)


@pytest.mark.slow
@pytest.mark.serial
@pytest.mark.chaos
def test_r5_broker_outage_fails_closed_and_bulkhead_rejects_excess_calls(
        http, user_pool, db, redis_cli, new_seckill_voucher, start_voucher,
        stop_service):
    """R5：broker 中断时只允许快速失败，事务 Lua 与 DB 都不能产生领取。"""
    users = user_pool(150)
    with new_seckill_voucher(stock=200) as voucher_id:
        start_voucher(voucher_id, users[0])
        gate = Barrier(len(users))

        def claim(auth):
            gate.wait()
            return http.post(
                f"/voucher-order/seckill/{voucher_id}", auth=auth, timeout=12,
            )

        with stop_service("rocketmq-broker"):
            with ThreadPoolExecutor(max_workers=len(users)) as pool:
                responses = list(pool.map(claim, users))

        assert all(
            response.http_status == 503 or response.code == 5004
            for response in responses
        ), [(response.http_status, response.code) for response in responses]
        assert not any(response.body and response.body.get("success") is True
                       for response in responses)
        assert db.query_value(
            "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = %s",
            (voucher_id,),
        ) == 0
        assert redis_cli.get(keys.seckill_stock(voucher_id)) == "200"


@pytest.mark.slow
@pytest.mark.serial
@pytest.mark.chaos
def test_r7_redis_outage_rejects_server_side_session_and_recovers_cleanly(
        http, user, db, redis_cli, new_seckill_voucher, start_voucher,
        stop_service):
    """R7：Redis 全停时无法确认服务端会话，返回 401；恢复后账本仍可正常领取。"""
    with new_seckill_voucher(stock=1) as voucher_id:
        start_voucher(voucher_id, user)

        with stop_service("redis"):
            unavailable = http.post(
                f"/voucher-order/seckill/{voucher_id}", auth=user, timeout=12,
            )

        assert unavailable.http_status == 401
        assert unavailable.body is None
        assert db.query_value(
            "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id = %s",
            (voucher_id,),
        ) == 0

        recovered = wait_until(
            lambda: (
                response
                if (response := order_api.seckill(http, voucher_id, user)).body
                and response.body.get("success") is True
                else None
            ),
            timeout=15,
            interval=0.5,
            desc="等待 Redis 恢复后领取链路可用",
        )
        assert_result(recovered, success=True)
        _assert_four_way_reconciliation(db, redis_cli, voucher_id, 1, 1, timeout=60)


def test_w5_ended_window_takes_priority_over_repeat_claim(
        http, user, reset_rate_limit, new_seckill_voucher, start_voucher, end_voucher):
    """W5：结束窗口判断发生在 Lua 去重前，老用户也应得到 ended。"""
    with new_seckill_voucher(stock=2) as voucher_id:
        start_voucher(voucher_id, user)
        assert_result(order_api.seckill(http, voucher_id, user), success=True)
        end_voucher(voucher_id)
        reset_rate_limit(user.user_id)

        response = order_api.seckill(http, voucher_id, user)

        assert_result(response, success=False, code=1008)


@pytest.mark.isolate
def test_w7_missing_voucher_is_rejected_and_negative_cached(
        http, user, redis_cli, reset_rate_limit, metrics):
    """W7：不存在的券连续请求都拒绝，第二次由空值 meta 拦截穿透。"""
    voucher_id = 999_999_991
    meta_key = keys.seckill_meta(voucher_id)
    redis_cli.delete(meta_key)
    reset_rate_limit(user.user_id)
    try:
        with metrics.snapshot() as snapshot:
            responses = [order_api.seckill(http, voucher_id, user) for _ in range(2)]

        assert all(response.code == 1009 for response in responses)
        assert redis_cli.hget(meta_key, "none") == "1"
        assert 1 <= redis_cli.ttl(meta_key) <= 120
        snapshot.delta_eq("hmdp.seckill.result", {"reason": "voucher_not_seckill"}, 2)
    finally:
        redis_cli.delete(meta_key)


@pytest.mark.isolate
def test_r1_sixth_submission_is_rejected_by_application_rate_limit(
        http, user, metrics, new_seckill_voucher, start_voucher):
    """R1：同一用户 1 秒内第 6 次提交由应用层拒绝。"""
    with new_seckill_voucher(stock=10) as voucher_id:
        start_voucher(voucher_id, user)
        with metrics.snapshot() as snapshot:
            responses = [order_api.seckill(http, voucher_id, user) for _ in range(6)]

        assert all(response.http_status == 200 for response in responses[:5])
        rejected = responses[5]
        assert_result(rejected, http_status=429, msg_contains="请求过于频繁")
        assert "X-RateLimit-Layer" not in rejected.headers
        snapshot.delta_eq("hmdp.seckill.result", {"reason": "rate_limited"}, 1)


@pytest.mark.isolate
def test_r2_submission_and_result_query_have_independent_quotas(
        http, user, metrics, new_seckill_voucher, start_voucher):
    """R2：烧光提交配额不影响查询；查询第 11 次单独被拒。"""
    with new_seckill_voucher(stock=10) as voucher_id:
        start_voucher(voucher_id, user)
        submissions = [order_api.seckill(http, voucher_id, user) for _ in range(6)]
        order_id = submissions[0].data
        assert submissions[5].http_status == 429

        with metrics.snapshot() as snapshot:
            queries = [order_api.seckill_result(http, order_id, user) for _ in range(11)]

        assert all(response.http_status == 200 for response in queries[:10])
        assert_result(queries[10], http_status=429, msg_contains="请求过于频繁")
        snapshot.delta_eq("hmdp.ratelimit.fallback", {"strategy": "rejected"}, 1)
        snapshot.assert_no_increase("hmdp.seckill.result", {})


def test_r3_submission_quota_recovers_after_sliding_window_moves(
        http, user, new_seckill_voucher, start_voucher):
    """R3：1 秒窗口移过后提交重新放行到业务层。"""
    with new_seckill_voucher(stock=10) as voucher_id:
        start_voucher(voucher_id, user)
        responses = [order_api.seckill(http, voucher_id, user) for _ in range(6)]
        assert responses[5].http_status == 429

        recover_at = time.monotonic() + 1.1
        wait_until(lambda: time.monotonic() >= recover_at, timeout=2, interval=0.02,
                   desc="等待提交滑动窗口移过")

        recovered = order_api.seckill(http, voucher_id, user)
        assert_result(recovered, success=False, code=1004)
