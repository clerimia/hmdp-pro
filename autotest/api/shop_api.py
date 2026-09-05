"""商铺接口（ShopController，2026-09-05 核实）。/shop/** 免登录。"""
from __future__ import annotations

from typing import Optional

from common.client import ApiClient, ApiResponse, AuthContext


def query_by_id(client: ApiClient, shop_id: int) -> ApiResponse:
    """GET /shop/{id} —— 两级缓存读链路的主被测接口。"""
    return client.get(f"/shop/{shop_id}")


def benchmark_redis(client: ApiClient, shop_id: int) -> ApiResponse:
    """GET /shop/benchmark/redis/{id} —— 压测对照：原版 Redis → MySQL。"""
    return client.get(f"/shop/benchmark/redis/{shop_id}")


def benchmark_db(client: ApiClient, shop_id: int) -> ApiResponse:
    """GET /shop/benchmark/db/{id} —— 压测对照：直查 MySQL。"""
    return client.get(f"/shop/benchmark/db/{shop_id}")


def update_shop(client: ApiClient, shop: dict, auth: Optional[AuthContext] = None) -> ApiResponse:
    """PUT /shop —— 更新商铺（B1 写后立即生效的触发点；afterCommit 内 evict）。"""
    return client.put("/shop", json=shop, auth=auth)
