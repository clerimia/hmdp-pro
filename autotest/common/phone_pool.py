"""测试手机号段分配器（框架结构票 §2）。

- 号段 138001 + 5 位序号 → 13800100000 起，落在 130-139 合法段内；
- 分配幂等且可预测：按序号发号不随机，用例报失败时能从手机号反推是第几个用户
  （index_of 反查）；
- 后端 login 对不存在手机号自动注册（UserServiceImpl#createUserWithPhone），
  所以不需要任何预置 SQL。
"""
from __future__ import annotations

import threading
from typing import List


class PhonePool:

    def __init__(self, prefix: str = "138001", start: int = 0, width: int = 5):
        if len(prefix) + width != 11:
            raise ValueError(f"号段长度必须凑成 11 位手机号: prefix={prefix!r} width={width}")
        self._prefix = prefix
        self._width = width
        self._next = start
        self._lock = threading.Lock()

    def _fmt(self, index: int) -> str:
        return f"{self._prefix}{index:0{self._width}d}"

    def take(self, n: int) -> List[str]:
        """顺序取 n 个号。同一进程内永不重复；跨用例连续编号。"""
        if n <= 0:
            return []
        with self._lock:
            phones = [self._fmt(i) for i in range(self._next, self._next + n)]
            self._next += n
        return phones

    def next(self) -> str:
        return self.take(1)[0]

    def index_of(self, phone: str) -> int:
        """从手机号反推序号——用例失败时定位是第几个用户。"""
        if not phone.startswith(self._prefix):
            raise ValueError(f"不在号段内: {phone!r}")
        return int(phone[len(self._prefix):])

    @property
    def allocated(self) -> int:
        with self._lock:
            return self._next
