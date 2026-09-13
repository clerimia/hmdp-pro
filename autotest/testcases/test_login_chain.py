"""登录链路 29 条自动化接口测试；HTTP 主断言，Redis/MySQL 只作状态 seam。"""
from concurrent.futures import ThreadPoolExecutor

import pytest
from api import user_api
from common import keys
from common.client import ApiClient, AuthContext
from common.wait import wait_until

def fail(r, msg="手机号格式错误！"):
    assert r.http_status == 200 and r.body["success"] is False and r.error_msg == msg

def auth(phone, token, prefix=""):
    return AuthContext(phone=phone, token=token, headers={"authorization": prefix + token})

def test_s01_phone_required(http):
    assert http.post("/user/code", params={}).http_status == 400

def test_s02_blank_and_letters(http):
    for p in ("", "abcdefghijk"): fail(user_api.send_code(http, p))

def test_s03_length_boundaries(http):
    for p in ("1380000000", "138000000000"): fail(user_api.send_code(http, p))

def test_s04_country_prefix(http):
    fail(user_api.send_code(http, "+8613800000001"))

def test_s05_duplicate_send_keeps_current_code(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; current = sms_code(p)
    assert user_api.send_code(http, p).body["success"] is True
    assert redis_cli.get(keys.login_code(p)) == current
    assert user_api.login(http, p, current).body["success"] is True

def test_s06_duplicate_send_is_idempotent_during_cooldown(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; current = sms_code(p)
    rs = [user_api.send_code(http, p) for _ in range(4)]
    assert all(r.body["success"] is True for r in rs)
    assert redis_cli.get(keys.login_code(p)) == current
    assert 1 <= redis_cli.ttl(keys.login_code_cooldown(p)) <= 60

def test_l01_phone_required(http):
    fail(http.post("/user/login", json={"code": "123456"}))

def test_l02_phone_length(http):
    for p in ("1380000000", "138000000000"): fail(user_api.login(http, p, "123456"))

def test_l03_country_prefix(http):
    fail(user_api.login(http, "+8613800000001", "123456"))

def test_l04_code_not_sent(http, phone_pool, redis_cli):
    p = phone_pool.take(1)[0]
    redis_cli.delete(keys.login_code(p), keys.login_attempts(p), keys.login_locked(p))
    fail(user_api.login(http, p, "123456"), "验证码错误")

def test_l05_wrong_code(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; code = sms_code(p)
    fail(user_api.login(http, p, "000000" if code != "000000" else "999999"), "验证码错误")

def test_l06_malformed_code(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; sms_code(p); fail(user_api.login(http, p, "12345"), "验证码错误")

def test_l07_expired_code(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; code = sms_code(p); redis_cli.expire(keys.login_code(p), 1)
    wait_until(lambda: not redis_cli.exists(keys.login_code(p)), timeout=3, interval=.05, desc="验证码过期")
    fail(user_api.login(http, p, code), "验证码错误")

def test_l09_session_and_me(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; token = user_api.login(http, p, sms_code(p)).data
    session = redis_cli.hgetall(keys.login_token(token)); me = user_api.me(http, auth(p, token))
    assert len(token) == 32 and session["id"] == str(me.data["id"])

def test_l10_auto_register(http, phone_pool, sms_code, db):
    p = phone_pool.take(1)[0]; db.execute("DELETE FROM tb_user WHERE phone=%s", (p,))
    try:
        assert user_api.login(http, p, sms_code(p)).body["success"] is True
        assert db.query_value("SELECT COUNT(*) FROM tb_user WHERE phone=%s", (p,)) == 1
    finally: db.execute("DELETE FROM tb_user WHERE phone=%s", (p,))

def test_l11_code_single_use(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; code = sms_code(p)
    assert user_api.login(http, p, code).body["success"] is True
    fail(user_api.login(http, p, code), "验证码错误")

def test_l12_sliding_ttl(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; token = user_api.login(http, p, sms_code(p)).data
    redis_cli.expire(keys.login_token(token), 60)
    assert user_api.me(http, auth(p, token)).http_status == 200
    assert 1790 <= redis_cli.ttl(keys.login_token(token)) <= 1800

def test_l13_missing_token(http): assert user_api.me(http).http_status == 401

def test_l14_unknown_token(http): assert user_api.me(http, auth("", "f" * 32)).http_status == 401

def test_l15_logout(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; token = user_api.login(http, p, sms_code(p)).data; a = auth(p, token)
    assert user_api.logout(http, a).body["success"] is True
    assert user_api.me(http, a).http_status == 401
    assert user_api.logout(http, a).body["success"] is True

def test_l16_bearer_rejected(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; token = user_api.login(http, p, sms_code(p)).data
    assert user_api.me(http, auth(p, token, "Bearer ")).http_status == 401

def test_l17_wrong_code_lock(http, phone_pool, sms_code, redis_cli):
    p = phone_pool.take(1)[0]; code = sms_code(p); wrong = "000000" if code != "000000" else "999999"
    assert all(not user_api.login(http, p, wrong).body["success"] for _ in range(5))
    fail(user_api.login(http, p, code), "验证码错误次数过多，请稍后重试")
    redis_cli.delete(keys.login_attempts(p), keys.login_locked(p))

@pytest.mark.slow
@pytest.mark.serial
@pytest.mark.chaos
def test_l18_redis_timeout_returns_401(http, redis_cli):
    redis_cli.raw.execute_command("CLIENT", "PAUSE", 2000, "ALL")
    response = user_api.me(http, auth("", "a" * 32))
    assert response.http_status == 401

def test_l19_malformed_code_does_not_block_followup_login(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; code = sms_code(p)
    fail(user_api.login(http, p, "12345"), "验证码错误")
    assert user_api.login(http, p, code).body["success"] is True

def test_l20_wrong_code_below_limit_allows_followup_login(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; code = sms_code(p)
    wrong = "000000" if code != "000000" else "999999"
    for _ in range(4):
        fail(user_api.login(http, p, wrong), "验证码错误")
    assert user_api.login(http, p, code).body["success"] is True

@pytest.mark.serial
def test_l21_concurrent_login_consumes_code_once(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]; code = sms_code(p)
    clients = [ApiClient(http.base_url, timeout=http.timeout) for _ in range(2)]
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(lambda client: user_api.login(client, p, code), clients))
    finally:
        for client in clients:
            client._session.close()

    assert sum(r.http_status == 200 and r.body["success"] is True for r in responses) == 1
    failures = [r for r in responses if not r.body["success"]]
    assert len(failures) == 1 and failures[0].error_msg == "验证码错误"

def test_l22_relogin_returns_new_token_and_keeps_both_sessions(
        http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]
    first_token = user_api.login(http, p, sms_code(p)).data
    first_me = user_api.me(http, auth(p, first_token))

    second_token = user_api.login(http, p, sms_code(p)).data
    second_me = user_api.me(http, auth(p, second_token))
    old_session_me = user_api.me(http, auth(p, first_token))

    assert first_token != second_token
    assert first_me.http_status == second_me.http_status == old_session_me.http_status == 200
    assert first_me.data["id"] == second_me.data["id"] == old_session_me.data["id"]

def test_l23_logout_revokes_only_current_token(http, phone_pool, sms_code):
    p = phone_pool.take(1)[0]
    first_token = user_api.login(http, p, sms_code(p)).data
    second_token = user_api.login(http, p, sms_code(p)).data

    assert user_api.logout(http, auth(p, second_token)).body["success"] is True
    assert user_api.me(http, auth(p, second_token)).http_status == 401
    assert user_api.me(http, auth(p, first_token)).http_status == 200

def test_l24_logout_without_token_is_idempotent(http):
    response = user_api.logout(http)
    assert response.http_status == 200 and response.body["success"] is True
