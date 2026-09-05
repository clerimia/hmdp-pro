"""优惠券接口（VoucherController，2026-09-05 核实）。/voucher/** 免登录。"""
from __future__ import annotations

from typing import Optional

from common.client import ApiClient, ApiResponse, AuthContext


def add_seckill_voucher(client: ApiClient, voucher: dict,
                        auth: Optional[AuthContext] = None) -> ApiResponse:
    """POST /voucher/seckill —— 新增秒杀券，data=券 id。

    body 是 Voucher 实体：shopId/title/subTitle/rules/payValue/actualValue/type=1/
    stock/beginTime/endTime（时间格式 yyyy-MM-dd HH:mm:ss）。免登录。
    """
    return client.post("/voucher/seckill", json=voucher, auth=auth)


def list_of_shop(client: ApiClient, shop_id: int) -> ApiResponse:
    """GET /voucher/list/{shopId} —— 店铺券列表。"""
    return client.get(f"/voucher/list/{shop_id}")
