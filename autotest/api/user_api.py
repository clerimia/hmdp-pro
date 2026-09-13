"""用户接口（UserController，2026-09-05 核实）。"""
from __future__ import annotations

from common.client import ApiClient, ApiResponse, AuthContext


def send_code(client: ApiClient, phone: str) -> ApiResponse:
    """POST /user/code —— 发验证码；60 秒冷却期内重复请求幂等成功。"""
    return client.post("/user/code", params={"phone": phone})


def login(client: ApiClient, phone: str, code: str) -> ApiResponse:
    """POST /user/login —— 校验码登录，成功 data=token（32 位无横线 UUID）。

    验证码原子消费；用户不存在自动注册。
    """
    return client.post("/user/login", json={"phone": phone, "code": code})


def me(client: ApiClient, auth: AuthContext | None = None) -> ApiResponse:
    """GET /user/me —— 当前登录用户（UserDTO）。需登录。"""
    return client.get("/user/me", auth=auth)


def logout(client: ApiClient, auth: AuthContext | None = None) -> ApiResponse:
    """POST /user/logout —— 删除当前 token；重复调用保持幂等。"""
    return client.post("/user/logout", auth=auth)
