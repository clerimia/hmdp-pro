"""Prometheus 指标抓取与 delta 计算——第四类断言助手（本项目特色）。

黑盒抓 /actuator/prometheus，断言系统「自己认为发生了什么」，与 HTTP 返回值、
DB/Redis 状态交叉验证。

两条纪律（两级缓存票 §1 / map Notes，写用例前必读）：
1. **结果型 vs 路径型**：结果型指标（一次一计、取值互斥，如 hmdp.seckill.result{reason}）
   可断 delta == 1；路径型指标（一次请求可叠加多次，如 hmdp.cache.hit{level}）只能
   delta >= 1。全项目唯一可精确断言的缓存指标是 hit{level=l1}。
2. **指标是辅助，不是主依据**：它是被测代码自己 increment 的，循环论证；
   主依据永远是 HTTP 响应 + 直连 Redis/MySQL。

工程细节（两级缓存票 §6.1）：
- 序列缺失 = 计数 0（Micrometer Counter 首次 increment 前不导出该序列，必须按 0 处理）；
- 按 application tag 过滤（默认 hmdp-pro），同机其他实例不污染；
- 指标名换算：用例写点分名（hmdp.seckill.result），本模块换 prometheus 名
  （hmdp_seckill_result_total）；Counter 自动补 _total，Gauge（如
  resilience4j_circuitbreaker_state）不补——先精确匹配，未命中再试 _total。
"""
from __future__ import annotations

import re
from typing import Dict, Mapping, Optional, Tuple

import requests

# 形如  name{k="v",k2="v2",}  123.0   （Java client 的 tag 列表可能带尾逗号）
_LINE_RE = re.compile(
    r'^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)'
    r'(?:\{(?P<tags>[^}]*)\})?'
    r'\s+(?P<value>[^\s]+)$'
)

SeriesKey = Tuple[str, frozenset]


def _parse_exposition(text: str, application: str) -> Dict[SeriesKey, float]:
    series: Dict[SeriesKey, float] = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = _LINE_RE.match(line)
        if not match:
            continue
        name = match.group("name")
        tags: Dict[str, str] = {}
        raw_tags = match.group("tags")
        if raw_tags:
            for part in raw_tags.split(","):
                part = part.strip()
                if not part:
                    continue  # 尾逗号
                key, _, value = part.partition("=")
                tags[key.strip()] = value.strip().strip('"')
        # application tag 过滤：同机其他实例不污染（无 application tag 的序列保留）
        if "application" in tags and tags["application"] != application:
            continue
        try:
            value = float(match.group("value"))
        except ValueError:
            continue  # NaN/Inf 之外的脏行直接跳过
        series[(name, frozenset(tags.items()))] = value
    return series


def _prom_name(name: str) -> str:
    """点分名 → prometheus 名。"""
    return name.replace(".", "_")


def _match(series: Dict[SeriesKey, float], name: str,
           tags: Optional[Mapping[str, str]]) -> Dict[SeriesKey, float]:
    """精确名优先，未命中再试补 _total；tags 为子集匹配（caller 不用带 application）。"""
    wanted = dict(tags or {})
    for candidate in (_prom_name(name), _prom_name(name) + "_total"):
        matched = {
            (n, t): v for (n, t), v in series.items()
            if n == candidate and wanted.items() <= t
        }
        if matched:
            return matched
    return {}


class MetricsHelper:

    def __init__(self, scrape_url: str, application: str = "hmdp-pro", timeout: float = 5.0):
        """scrape_url 通常 = 应用 base_url + /actuator/prometheus（默认直连应用，
        不走 Prometheus 服务端——没有 scrape_interval 滞后，delta 更即时）。"""
        self.scrape_url = scrape_url.rstrip("/")
        self.application = application
        self.timeout = timeout
        self._session = requests.Session()

    def scrape(self) -> Dict[SeriesKey, float]:
        resp = self._session.get(self.scrape_url, timeout=self.timeout)
        resp.raise_for_status()
        return _parse_exposition(resp.text, self.application)

    def value(self, name: str, tags: Optional[Mapping[str, str]] = None) -> float:
        """当前绝对值（Gauge / 熔断状态用）。序列缺失 = 0。"""
        matched = _match(self.scrape(), name, tags)
        return sum(matched.values()) if matched else 0.0

    def snapshot(self) -> "MetricsSnapshot":
        return MetricsSnapshot(self, self.scrape())


class MetricsSnapshot:
    """基线快照；delta 语义 = 现值 - 基线（序列缺失按 0 计）。

    支持 with 语法（规格 §4.4 标准用法）::

        with metrics.snapshot() as snap:      # 进入时抓基线
            ...  # 执行操作
        snap.delta_ge("hmdp.seckill.result", {"reason": "stock_out"}, 1)

    delta 在调用时重抓一次现值求差，所以断言写在 with 内外都成立。
    """

    def __init__(self, helper: MetricsHelper, baseline: Dict[SeriesKey, float]):
        self._helper = helper
        self._baseline = baseline

    def __enter__(self) -> "MetricsSnapshot":
        return self

    def __exit__(self, exc_type, exc, tb) -> bool:
        return False   # 不吞异常

    def delta(self, name: str, tags: Optional[Mapping[str, str]] = None) -> float:
        current = _match(self._helper.scrape(), name, tags)
        base = _match(self._baseline, name, tags)
        keys = set(current) | set(base)
        return sum(current.get(k, 0.0) for k in keys) - sum(base.get(k, 0.0) for k in keys)

    def delta_ge(self, name: str, tags: Optional[Mapping[str, str]], n: float) -> None:
        actual = self.delta(name, tags)
        assert actual >= n, f"指标 {name}{tags or {}} 增量 {actual} 应 >= {n}"

    def delta_eq(self, name: str, tags: Optional[Mapping[str, str]], n: float) -> None:
        """只许用在结果型指标上（一次一计、取值互斥）；路径型用 delta_ge。"""
        actual = self.delta(name, tags)
        assert actual == n, f"指标 {name}{tags or {}} 增量 {actual} 应 == {n}"

    def assert_no_increase(self, name: str, tags: Optional[Mapping[str, str]] = None) -> None:
        actual = self.delta(name, tags)
        assert actual == 0, f"指标 {name}{tags or {}} 增量 {actual} 应 == 0（不该发生）"

    def value(self, name: str, tags: Optional[Mapping[str, str]] = None) -> float:
        return self._helper.value(name, tags)
