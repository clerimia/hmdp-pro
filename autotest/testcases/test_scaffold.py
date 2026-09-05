"""框架自检（脚手架冒烟）——不依赖应用/中间件运行，纯本地逻辑验证。

覆盖：配置合并与环境变量插值、号段分配器、Redis key 对齐、错误码镜像、
wait_until 语义、ApiResponse 两级世界观、指标名换算、数据加载器。
跑通这套 = 脚手架本身可用；链路用例归执行⑥/⑦/⑧票。
"""
from __future__ import annotations

import pytest

from common import error_codes, keys
from common.client import ApiResponse
from common.config import Config
from common.data_loader import load_cases
from common.metrics import _match, _parse_exposition, _prom_name
from common.phone_pool import PhonePool
from common.wait import wait_until


# ============================================================ 配置 ====
class TestConfig:

    def test_merge_local_env(self):
        cfg = Config("local")
        # 断言的是 env.local.yaml 的「声明值」（期望值锚定，非连接目标——
        # 用例运行期的地址永远走 cfg，此处硬编码恰是配置漂移的哨兵）
        assert cfg.base_url == "http://127.0.0.1:8081"
        assert int(cfg.mysql.port) == 3307          # 容器 3306 → 宿主机 3307
        assert cfg.mysql.database == "hmdp"
        # config.yaml 的默认键仍在（deep merge 不丢）
        assert float(cfg.http.timeout) == 5.0
        assert int(cfg.wait.interval) == 0 or cfg.wait.interval == 0.2

    def test_env_var_interpolation(self, monkeypatch):
        monkeypatch.setenv("HMDP_DB_USER", "pytest_user")
        monkeypatch.setenv("HMDP_DB_PASSWORD", "secret")
        cfg = Config("local")
        assert cfg.mysql.user == "pytest_user"
        assert cfg.mysql.password == "secret"

    def test_env_var_default_when_unset(self, monkeypatch):
        monkeypatch.delenv("HMDP_DB_USER", raising=False)
        monkeypatch.delenv("HMDP_DB_PASSWORD", raising=False)
        cfg = Config("local")
        assert cfg.mysql.user == "root"
        assert cfg.mysql.password == ""

    def test_base_url_override(self):
        cfg = Config("local", base_url_override="http://127.0.0.1")   # 切网关入口
        assert cfg.base_url == "http://127.0.0.1"

    def test_unknown_key_raises(self):
        cfg = Config("local")
        with pytest.raises(AttributeError, match="not_exist"):
            _ = cfg.not_exist


# ============================================================ 号段 ====
class TestPhonePool:

    def test_sequential_and_predictable(self):
        pool = PhonePool(prefix="138001", start=0, width=5)
        assert pool.take(3) == ["13800100000", "13800100001", "13800100002"]
        assert pool.take(2) == ["13800100003", "13800100004"]     # 跨调用连续

    def test_index_of_roundtrip(self):
        pool = PhonePool(prefix="138001", start=0, width=5)
        phones = pool.take(3)
        assert [pool.index_of(p) for p in phones] == [0, 1, 2]    # 失败时反推第几个用户

    def test_invalid_width(self):
        with pytest.raises(ValueError, match="11 位"):
            PhonePool(prefix="138", width=5)


# ============================================================ key / 错误码镜像 ====
class TestMirrors:

    def test_keys_aligned_with_java_constants(self):
        assert keys.login_code("13800000001") == "login:code:13800000001"
        assert keys.login_token("abc") == "login:token:abc"
        assert keys.cache_shop(1) == "cache:shop:1"
        assert keys.lock_shop(1) == "lock:shop:1"
        assert keys.seckill_stock(10) == "seckill:stock:10"
        assert keys.seckill_meta(10) == "seckill:meta:10"
        assert keys.seckill_order(10) == "seckill:order:10"
        assert keys.seckill_claim(10) == "seckill:claim:10"
        assert keys.seckill_txn(10) == "seckill:txn:10"
        assert keys.seckill_queue(123) == "seckill:queue:123"
        assert keys.rate_sw_seckill(7) == "rate:sw:seckill:7"
        assert keys.rate_sw_seckill_result(7) == "rate:sw:seckill:result:7"

    def test_error_codes_mirror(self):
        assert error_codes.STOCK_OUT == 1003
        assert error_codes.ORDER_REPEAT == 1004
        assert error_codes.SECKILL_NOT_STARTED == 1007
        assert error_codes.SECKILL_ENDED == 1008
        assert error_codes.VOUCHER_NOT_SECKILL == 1009
        assert error_codes.ORDER_PROCESSING == 1100
        assert error_codes.SYS_REDIS_UNAVAILABLE == 5002
        assert error_codes.SYS_MQ_UNAVAILABLE == 5004


# ============================================================ wait_until ====
class TestWaitUntil:

    def test_returns_predicate_value(self):
        result = wait_until(lambda: "payload", timeout=0.5, interval=0.01)
        assert result == "payload"

    def test_polls_until_truthy(self):
        state = {"n": 0}

        def predicate():
            state["n"] += 1
            return state["n"] >= 3

        wait_until(predicate, timeout=2.0, interval=0.01, desc="计数到 3")
        assert state["n"] >= 3

    def test_timeout_raises_with_snapshot(self):
        with pytest.raises(AssertionError, match="快照"):
            wait_until(lambda: None, timeout=0.1, interval=0.02, desc="永远不满足")

    def test_transient_exception_retried(self):
        state = {"calls": 0}

        def flaky():
            state["calls"] += 1
            if state["calls"] < 2:
                raise ConnectionError("轮询期瞬时故障")
            return "ok"

        assert wait_until(flaky, timeout=1.0, interval=0.01) == "ok"


# ============================================================ 响应模型 ====
class TestApiResponse:

    def test_business_body(self):
        resp = ApiResponse(200, {"success": False, "code": 1003,
                                 "errorMsg": "库存不足", "data": None}, {})
        assert resp.code == 1003
        assert resp.error_msg == "库存不足"
        assert resp.data is None

    def test_401_empty_body(self):
        resp = ApiResponse(401, None, {})
        assert resp.body is None
        assert resp.code is None          # 不许去解 body
        assert resp.error_msg is None

    def test_trace_id_from_header(self):
        resp = ApiResponse(503, {"success": False, "code": 5002}, {"X-Trace-Id": "abc123"})
        assert resp.trace_id == "abc123"


# ============================================================ 指标 ====
_SAMPLE = """\
# HELP hmdp_cache_hit_total counter
# TYPE hmdp_cache_hit_total counter
hmdp_cache_hit_total{application="hmdp-pro",level="l1",} 3.0
hmdp_cache_hit_total{application="hmdp-pro",level="l2",} 7.0
hmdp_seckill_result_total{application="hmdp-pro",reason="stock_out",} 1.0
resilience4j_circuitbreaker_state{application="hmdp-pro",name="redisBreaker",} 0.0
other_app_metric_total{application="someone-else",} 99.0
"""


class TestMetrics:

    def test_parse_exposition(self):
        series = _parse_exposition(_SAMPLE, application="hmdp-pro")
        assert series[("hmdp_cache_hit_total", frozenset(
            {"application": "hmdp-pro", "level": "l1"}.items()))] == 3.0
        # application 过滤：别的实例不污染
        assert not any("someone-else" in dict(t).values() for _, t in series)

    def test_dotted_name_fallback_total(self):
        series = _parse_exposition(_SAMPLE, application="hmdp-pro")
        # 点分名 → 自动补 _total
        matched = _match(series, "hmdp.seckill.result", {"reason": "stock_out"})
        assert sum(matched.values()) == 1.0
        # Gauge 名不补 _total 也能精确命中
        matched = _match(series, "resilience4j.circuitbreaker.state", {"name": "redisBreaker"})
        assert sum(matched.values()) == 0.0

    def test_missing_series_is_zero(self):
        series = _parse_exposition(_SAMPLE, application="hmdp-pro")
        assert _match(series, "hmdp.never.emitted", None) == {}   # 缺失 = 0

    def test_partial_tag_match(self):
        series = _parse_exposition(_SAMPLE, application="hmdp-pro")
        matched = _match(series, "hmdp_cache_hit_total", None)    # 不筛 level，两序列求和
        assert sum(matched.values()) == 10.0

    def test_snapshot_context_manager_protocol(self):
        """with metrics.snapshot() as snap 是规格 §4.4 标准用法，协议必须有。"""
        from common.metrics import MetricsHelper, MetricsSnapshot

        class _StubHelper(MetricsHelper):
            def __init__(self):
                self._n = 1.0

            def scrape(self):
                series = _parse_exposition(
                    f'hmdp_cache_hit_total{{application="hmdp-pro",level="l1",}} {self._n}',
                    application="hmdp-pro",
                )
                self._n += 1.0
                return series

        helper = _StubHelper()
        with helper.snapshot() as snap:
            assert isinstance(snap, MetricsSnapshot)
            snap._helper._n += 5.0        # with 内推进计数（模拟业务操作让指标增长）
        # 基线抓到 1.0；delta 重抓到 7.0 → 增量 6.0（断言写在 with 外也成立）
        snap.delta_eq("hmdp.cache.hit", {"level": "l1"}, 6.0)


# ============================================================ 数据层 ====
class TestDataLoader:

    @pytest.mark.parametrize("name", ["login_cases", "cache_cases", "seckill_cases"])
    def test_yaml_files_are_lists(self, name):
        cases = load_cases(name)
        assert isinstance(cases, list)     # 空列表 = 用例票尚未填充，结构合法

    def test_missing_file_raises(self):
        with pytest.raises(FileNotFoundError):
            load_cases("no_such_cases")


# ============================================================ 指标名换算单测 ====
def test_prom_name():
    assert _prom_name("hmdp.seckill.result") == "hmdp_seckill_result"
    assert _prom_name("hmdp_seckill_result_total") == "hmdp_seckill_result_total"
