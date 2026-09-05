"""订单接口（VoucherOrderController，2026-09-05 核实）。需登录 + 限流。"""
from __future__ import annotations

from common.client import ApiClient, ApiResponse, AuthContext


def seckill(client: ApiClient, voucher_id: int, auth: AuthContext | None = None) -> ApiResponse:
    """POST /voucher-order/seckill/{id} —— 抢券（术语：简历口述「抢券」，文档「领券」）。

    限流 5 次/秒/userId（key rate:sw:seckill:{uid}）；落库异步（RocketMQ 事务消息），
    订单断言必须 eventually 轮询。降级时 200 + code=1100(ORDER_PROCESSING) 且 data 带 orderId。
    """
    return client.post(f"/voucher-order/seckill/{voucher_id}", auth=auth)


def seckill_result(client: ApiClient, order_id: int, auth: AuthContext | None = None) -> ApiResponse:
    """GET /voucher-order/seckill/result/{orderId} —— 异步落库结果查询。

    限流 10 次/秒/userId（key rate:sw:seckill:result:{uid}，与提交配额独立）。
    """
    return client.get(f"/voucher-order/seckill/result/{order_id}", auth=auth)
