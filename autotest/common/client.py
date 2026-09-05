"""HTTP 客户端与响应模型（common 层）。

断言的两级世界观（框架结构票 §0/§4）：
- 未登录被拦成 HTTP 401 且 body 为空（LoginInterceptor 只 setStatus）；
- 业务失败是 200 + code；系统故障 503（带 X-Trace-Id）；限流 429（body 的 code 是 null）。

所以 ApiResponse.body 允许为 None，先解 HTTP 层再解业务层是所有断言助手的铁律。
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Any, Callable, Mapping, Optional

import requests

try:  # allure 不在依赖里时框架照常可用（只是没有附件）
    import allure
    _HAS_ALLURE = True
except ImportError:  # pragma: no cover
    _HAS_ALLURE = False


class ApiProtocolError(AssertionError):
    """协议级断言失败：接口应当返回 JSON 却没有。

    这是 api 层唯一允许的"断言"——HTTP 契约，不是业务结论（铁律见框架结构票 §1）。
    """


class TokenExpired(Exception):
    """带登录态的请求收到 401（AuthedClient 重登一次后仍 401 时抛出）。"""

    def __init__(self, phone: str, response: "ApiResponse"):
        self.phone = phone
        self.response = response
        super().__init__(f"用户 {phone} 登录态失效（重登后仍 401）: http={response.http_status}")


@dataclass
class ApiResponse:
    http_status: int
    body: dict | None                 # 401 时为 None（空 body，不许去解 JSON）
    headers: Mapping[str, str]        # requests 的 CaseInsensitiveDict，可直接 ["X-Trace-Id"]

    @property
    def code(self) -> int | None:
        return self.body.get("code") if self.body else None

    @property
    def data(self) -> Any:
        return self.body.get("data") if self.body else None

    @property
    def error_msg(self) -> str | None:
        return self.body.get("errorMsg") if self.body else None

    @property
    def trace_id(self) -> str | None:
        """系统故障时 WebExceptionAdvice 写入，用来串联 Java 侧日志。"""
        return self.headers.get("X-Trace-Id")


@dataclass
class AuthContext:
    """登录态：headers 可直接 ** 展开传给 api 层。

    client 由 conftest 的 login 工厂绑定（AuthedClient，401 自动失效重登一次）；
    测 401 行为的用例不要用 ctx.client，用裸 http + auth=ctx，避免重登干扰断言。
    """
    phone: str
    user_id: int | None = None
    token: str | None = None
    headers: dict = field(default_factory=dict)
    client: Any = field(default=None, compare=False, repr=False)

    def refresh(self, token: str, user_id: int | None) -> None:
        """重登后原地更新（token_cache 里的同一个对象保持有效）。"""
        self.token = token
        self.user_id = user_id
        self.headers = {"authorization": token}


def _mask(headers: Mapping[str, str] | None) -> dict:
    """附件里 token 打码。"""
    masked = dict(headers or {})
    if "authorization" in masked and masked["authorization"]:
        masked["authorization"] = str(masked["authorization"])[:8] + "***"
    return masked


class ApiClient:
    """requests.Session 封装：复用连接，返回 ApiResponse，不做任何业务断言。"""

    def __init__(self, base_url: str, timeout: float = 5.0, session: requests.Session | None = None):
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self._session = session or requests.Session()

    def request(self, method: str, path: str, *, params=None, json=None,
                headers=None, timeout=None, auth: AuthContext | None = None) -> ApiResponse:
        merged = dict(headers or {})
        if auth is not None:
            merged.update(auth.headers)
        url = f"{self.base_url}/{path.lstrip('/')}"
        raw = self._session.request(
            method, url, params=params, json=json, headers=merged,
            timeout=timeout or self.timeout,
        )
        resp = self._parse(method, url, merged, raw)
        return resp

    def get(self, path: str, **kw) -> ApiResponse:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw) -> ApiResponse:
        return self.request("POST", path, **kw)

    def put(self, path: str, **kw) -> ApiResponse:
        return self.request("PUT", path, **kw)

    def delete(self, path: str, **kw) -> ApiResponse:
        return self.request("DELETE", path, **kw)

    # ---- 内部 ----
    def _parse(self, method: str, url: str, req_headers: dict, raw: requests.Response) -> ApiResponse:
        body: dict | None
        if raw.status_code == 401 and not raw.text:
            body = None                      # LoginInterceptor 只 setStatus，body 为空
        else:
            try:
                parsed = raw.json()
            except ValueError as exc:
                self._attach(method, url, req_headers, None, raw)
                raise ApiProtocolError(
                    f"{method} {url} 应当返回 JSON，实际: http={raw.status_code} body={raw.text[:200]!r}"
                ) from exc
            body = parsed if isinstance(parsed, dict) else {"data": parsed}
        resp = ApiResponse(raw.status_code, body, raw.headers)
        self._attach(method, url, req_headers, None, raw)
        return resp

    def _attach(self, method: str, url: str, req_headers: dict, req_json, raw: requests.Response) -> None:
        """请求/响应自动挂 allure 附件（token 打码），用例不手写。"""
        if not _HAS_ALLURE:
            return
        request_text = json.dumps(
            {"method": method, "url": url, "headers": _mask(req_headers), "body": req_json},
            ensure_ascii=False, indent=2, default=str,
        )
        allure.attach(request_text, name="request", attachment_type=allure.attachment_type.JSON)
        response_text = json.dumps(
            {"http_status": raw.status_code, "body": raw.text[:4000],
             "trace_id": raw.headers.get("X-Trace-Id")},
            ensure_ascii=False, indent=2, default=str,
        )
        allure.attach(response_text, name="response", attachment_type=allure.attachment_type.JSON)


class AuthedClient:
    """带登录态的客户端视图：自动注入 authorization 头；401 时回调重登并重试一次。

    重登回调由 conftest 的 login 工厂提供（invalidate + 重新登录 + 原地 refresh AuthContext）。
    二次仍 401 抛 TokenExpired——token TTL 25 天且滑动续期，正常跑不会自然过期，
    这是防御性正确，不是兜底常事（框架结构票 §2）。
    """

    def __init__(self, base: ApiClient, auth: AuthContext, relogin: Callable[[], None]):
        self._base = base
        self._auth = auth
        self._relogin = relogin

    def request(self, method: str, path: str, **kw) -> ApiResponse:
        resp = self._base.request(method, path, auth=self._auth, **kw)
        if resp.http_status != 401:
            return resp
        self._relogin()
        resp = self._base.request(method, path, auth=self._auth, **kw)
        if resp.http_status == 401:
            raise TokenExpired(self._auth.phone, resp)
        return resp

    def get(self, path: str, **kw) -> ApiResponse:
        return self.request("GET", path, **kw)

    def post(self, path: str, **kw) -> ApiResponse:
        return self.request("POST", path, **kw)

    def put(self, path: str, **kw) -> ApiResponse:
        return self.request("PUT", path, **kw)

    def delete(self, path: str, **kw) -> ApiResponse:
        return self.request("DELETE", path, **kw)
