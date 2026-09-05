"""Redis key 构造——与 Java 侧常量一一对齐，禁止在用例里手拼字符串。

对齐来源（2026-09-05 核实）：
- src/main/java/com/hmdp/utils/RedisConstants.java
- src/main/java/com/hmdp/utils/RateLimitConstants.java
- VoucherOrderServiceImpl（seckill:test:protection 测试档位开关，3s 本地快照）

Java 侧常量改名时必须同步这里（镜像维护，同 common/error_codes.py 的纪律）。
"""
from __future__ import annotations

# ---- 登录（RedisConstants）----
LOGIN_CODE = "login:code:"        # LOGIN_CODE_KEY，TTL 2min
LOGIN_TOKEN = "login:token:"      # LOGIN_USER_KEY，hash（字段 id/nickName/avatar），TTL 36000min

# ---- 两级缓存（RedisConstants）----
CACHE_SHOP = "cache:shop:"        # CACHE_SHOP_KEY，RedisData JSON，物理 TTL 5400s
LOCK_SHOP = "lock:shop:"          # LOCK_SHOP_KEY，Redisson 锁（hash 结构，可被 HSET 伪造占位）

# ---- 抢券（RedisConstants）----
SECKILL_STOCK = "seckill:stock:"  # 库存
SECKILL_META = "seckill:meta:"    # 活动窗口元信息 hash，TTL 24h（改窗口必须 DEL）
SECKILL_ORDER = "seckill:order:"  # 成功用户集合（SADD），SCARD 对账依据
SECKILL_CLAIM = "seckill:claim:"  # hash：{userId: orderId} 原单号认领，补单复用原 orderId
SECKILL_TXN = "seckill:txn:"      # 事务消息本地标记
SECKILL_QUEUE = "seckill:queue:"  # 排队/结果状态（orderId 维度）
LOCK_SECKILL_WARM = "lock:seckill:warm:"  # 预热互斥锁

# ---- 限流（RateLimitConstants）----
RATE_SW_SECKILL = "rate:sw:seckill:"                # 领券提交，5 次/秒/userId
RATE_SW_SECKILL_RESULT = "rate:sw:seckill:result:"  # 结果查询，10 次/秒/userId，配额独立

# ---- 测试档位（VoucherOrderServiceImpl）----
SECKILL_TEST_PROTECTION = "seckill:test:protection"  # FULL/LEGACY/EARLY，切换后等 3s 快照过期


def login_code(phone: str) -> str:
    return LOGIN_CODE + phone


def login_token(token: str) -> str:
    return LOGIN_TOKEN + token


def cache_shop(shop_id: int) -> str:
    return f"{CACHE_SHOP}{shop_id}"


def lock_shop(shop_id: int) -> str:
    return f"{LOCK_SHOP}{shop_id}"


def seckill_stock(voucher_id: int) -> str:
    return f"{SECKILL_STOCK}{voucher_id}"


def seckill_meta(voucher_id: int) -> str:
    return f"{SECKILL_META}{voucher_id}"


def seckill_order(voucher_id: int) -> str:
    return f"{SECKILL_ORDER}{voucher_id}"


def seckill_claim(voucher_id: int) -> str:
    return f"{SECKILL_CLAIM}{voucher_id}"


def seckill_txn(voucher_id: int) -> str:
    return f"{SECKILL_TXN}{voucher_id}"


def seckill_queue(order_id: int) -> str:
    return f"{SECKILL_QUEUE}{order_id}"


def rate_sw_seckill(user_id: int) -> str:
    return f"{RATE_SW_SECKILL}{user_id}"


def rate_sw_seckill_result(user_id: int) -> str:
    return f"{RATE_SW_SECKILL_RESULT}{user_id}"
