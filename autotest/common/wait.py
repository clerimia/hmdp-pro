"""wait_until：异步落库等待的唯一正解（框架结构票纪律三）。

秒杀落库是异步的（RocketMQ 事务消息 → 消费者落库），DB/Redis/指标断言一律带
timeout 走这里轮询真实状态变化；禁止 time.sleep(3)——CI 机器慢一点就 flaky，
快一点又浪费时间。唯一可接受的固定等待是 TTL / 熔断的确定性等待（标 slow）。
"""
from __future__ import annotations

import time
from typing import Any, Callable


def wait_until(predicate: Callable[[], Any], *, timeout: float = 5.0,
               interval: float = 0.2, desc: str = "") -> Any:
    """轮询 predicate 直到返回真值；超时抛 AssertionError。

    - predicate 返回真值即成功，返回值原样带回（省一次重复查询）；
    - predicate 抛出的瞬时异常（轮询期内的连接抖动）记录后继续，不中断轮询；
    - 超时消息带上最后一次快照，失败时不用再手动查一遍。
    """
    deadline = time.monotonic() + timeout
    last: Any = None
    last_error: Exception | None = None
    while True:
        try:
            last = predicate()
            last_error = None
            if last:
                return last
        except Exception as exc:  # noqa: BLE001 —— 轮询期的瞬时故障重试到超时为止
            last_error = exc
        if time.monotonic() >= deadline:
            break
        time.sleep(interval)
    label = f"（{desc}）" if desc else ""
    raise AssertionError(
        f"wait_until 超时({timeout}s){label} 最后一次快照: {last!r}"
        + (f"，最后一次异常: {last_error!r}" if last_error else "")
    )
