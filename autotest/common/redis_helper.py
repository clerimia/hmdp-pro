"""RedisHelper：redis-py 直连 Redis（断言主依据之一 + 状态注入/故障注入载体）。

四条可复用手法的落点（两级缓存票 §7）：
- 状态注入：EXPIRE 1 过期 / DEL 会话失效 / 塞过期 JSON 模拟逻辑过期；
- 伪造 Redisson 锁：HSET + PEXPIRE 占位（锁是 hash，field 不等即加锁失败）；
- DEBUG SLEEP：阻塞整个 Redis 事件循环做依赖故障注入（chaos 用例专用）；
- SCAN 清理：上一轮压测的 seckill:* / rate:sw:* 残留。
"""
from __future__ import annotations

from typing import Iterator, List, Optional

import redis

from . import keys
from .wait import wait_until


class RedisHelper:

    def __init__(self, host: str, port: int, db: int = 0):
        self.raw = redis.Redis(host=host, port=int(port), db=int(db),
                               decode_responses=True, socket_timeout=5)

    # ---- 基础操作（薄封装，够用为止；不够的直接用 self.raw）----
    def get(self, key: str) -> Optional[str]:
        return self.raw.get(key)

    def set(self, key: str, value: str, ex: Optional[int] = None) -> None:
        self.raw.set(key, value, ex=ex)

    def delete(self, *keys_: str) -> int:
        return self.raw.delete(*keys_) if keys_ else 0

    def exists(self, key: str) -> bool:
        return bool(self.raw.exists(key))

    def ttl(self, key: str) -> int:
        return int(self.raw.ttl(key))

    def expire(self, key: str, seconds: int) -> None:
        self.raw.expire(key, seconds)

    def hset(self, key: str, field: str, value: str) -> None:
        self.raw.hset(key, field, value)

    def hget(self, key: str, field: str) -> Optional[str]:
        return self.raw.hget(key, field)

    def hgetall(self, key: str) -> dict:
        return self.raw.hgetall(key)

    def sadd(self, key: str, *members: str) -> int:
        return self.raw.sadd(key, *members)

    def srem(self, key: str, *members: str) -> int:
        return self.raw.srem(key, *members)

    def smembers(self, key: str) -> set:
        return self.raw.smembers(key)

    def scard(self, key: str) -> int:
        return int(self.raw.scard(key))

    def sismember(self, key: str, member: str) -> bool:
        return bool(self.raw.sismember(key, member))

    # ---- 测试专用 ----
    def wait_key(self, key: str, timeout: float = 2.0, interval: float = 0.05) -> str:
        """等 key 出现并返回值——登录取码用（发码是同步写 Redis，通常一轮就中）。"""
        return wait_until(lambda: self.get(key), timeout=timeout,
                          interval=interval, desc=f"等待 Redis key {key}")

    def fake_redisson_lock(self, key: str, lease_ms: int = 30000) -> None:
        """伪造 Redisson 锁占位（C4）：锁是 hash，key 已存在且 field 不等即加锁失败。"""
        self.raw.hset(key, "pytest-fake", "1")
        self.raw.pexpire(key, lease_ms)

    def debug_sleep(self, seconds: float) -> None:
        """阻塞整个 Redis 事件循环 N 秒（D1 chaos 注入）。

        实施前先实测 DEBUG 命令未被禁用（两级缓存票 §6.3 前置条件）。
        """
        self.raw.execute_command("DEBUG", "SLEEP", str(seconds))

    def scan_keys(self, pattern: str) -> List[str]:
        """SCAN 收集匹配 key（不用 KEYS——单实例也养成习惯）。"""
        found: List[str] = []
        cursor = 0
        while True:
            cursor, batch = self.raw.scan(cursor=cursor, match=pattern, count=200)
            found.extend(batch)
            if cursor == 0:
                return found

    def scan_delete(self, pattern: str) -> int:
        """按 pattern 清残留（压测前清 seckill:* / rate:sw:*）。"""
        matched = self.scan_keys(pattern)
        for key in matched:
            self.raw.delete(key)
        return len(matched)

    def clear_seckill_state(self, voucher_id: Optional[int] = None) -> None:
        """清一张券的全部 Redis 状态（teardown 用，warn 不 raise 的调用方负责）。"""
        suffix = "" if voucher_id is None else str(voucher_id)
        for prefix in (keys.SECKILL_STOCK, keys.SECKILL_META, keys.SECKILL_ORDER,
                       keys.SECKILL_CLAIM, keys.SECKILL_TXN):
            self.raw.delete(prefix + suffix)

    def close(self) -> None:
        self.raw.close()
