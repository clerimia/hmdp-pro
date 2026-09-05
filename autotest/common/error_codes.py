"""错误码镜像——与 com.hmdp.exception.ErrorCode 一一对应（2026-09-05 核实）。

Java 侧改码必须同步这里。号段约定：1xxx 参数与业务；11xx 降级专用；5xxx 系统与依赖故障。
业务错误 = HTTP 200 + code（不计入熔断）；系统错误 = HTTP 503 + code（计入熔断，带 X-Trace-Id）。
"""
from __future__ import annotations

# ---------------- 业务错误（1000+）：业务结果，不是故障 ----------------
PARAM_INVALID = 1001            # 参数错误
LOGIN_REQUIRED = 1002           # 请先登录
STOCK_OUT = 1003                # 库存不足
ORDER_REPEAT = 1004             # 不能重复领取
ORDER_NOT_FOUND = 1005          # 订单不存在
ORDER_CLOSED = 1006             # 订单已关闭
SECKILL_NOT_STARTED = 1007      # 活动尚未开始
SECKILL_ENDED = 1008            # 活动已结束
VOUCHER_NOT_SECKILL = 1009      # 该优惠券不是秒杀券

# ---------------- 降级专用（1100+）----------------
ORDER_PROCESSING = 1100         # 领取处理中（Redis 预扣成功但订单尚未落库，data 带 orderId）

# ---------------- 系统错误（5000+）：依赖故障，计入熔断 ----------------
SYS_BUSY = 5001                 # 熔断打开 / 舱壁已满（HTTP 503）
SYS_REDIS_UNAVAILABLE = 5002    # 缓存服务暂时不可用（HTTP 503）
SYS_DB_UNAVAILABLE = 5003       # 订单服务暂时不可用（HTTP 503）
SYS_MQ_UNAVAILABLE = 5004       # 领取通道暂时不可用（HTTP 503）
SYS_ERROR = 5999                # 系统异常（HTTP 503）
