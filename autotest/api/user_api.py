"""用户接口（UserController，2026-09-05 核实）。"""
from __future__ import annotations

from common.client import ApiClient, ApiResponse, AuthContext


def send_code(client: ApiClient, phone: str) -> ApiResponse:
    """POST /user/code —— 发验证码。免登录；无频控，每次覆盖旧码（TTL 2min）。"""
    return client.post("/user/code", params={"phone": phone})


def login(client: ApiClient, phone: str, code: str) -> ApiResponse:
    """POST /user/login —— 校验码登录，成功 data=token（32 位无横线 UUID）。

    码不是一次性的（登录成功不删码）；用户不存在自动注册。
    """
    return client.post("/user/login", json={"phone": phone, "code": code})


def me(client: ApiClient, auth: AuthContext | None = None) -> ApiResponse:
    """GET /user/me —— 当前登录用户（UserDTO）。需登录。"""
    return client.get("/user/me", auth=auth)
