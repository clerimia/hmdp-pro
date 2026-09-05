"""DbHelper：PyMySQL 直连 MySQL（断言主依据之一）。

时区纪律（框架结构票 §3，两级缓存票 §6.3）：时间注入统一用 SQL `NOW() + 偏移`，
与应用 System.currentTimeMillis() 同钟域；Python 侧不做本地时钟与 DB 时钟的换算。
autocommit=True——测试侧造数/改窗口要立刻对被测应用可见。
"""
from __future__ import annotations

from typing import Any, List, Optional

import pymysql


class DbHelper:

    def __init__(self, host: str, port: int, user: str, password: str,
                 database: str, charset: str = "utf8mb4"):
        self._kwargs = dict(host=host, port=int(port), user=user, password=password,
                            database=database, charset=charset,
                            autocommit=True, cursorclass=pymysql.cursors.DictCursor,
                            # 时区纪律（坑 #7）：session 时区钉死东八，SQL NOW() 与应用
                            # System.currentTimeMillis() 同钟域，窗口断言不因容器 TZ 漂移翻车
                            init_command="SET time_zone = '+08:00'")
        self._conn: Optional[pymysql.connections.Connection] = None

    # ---- 连接 ----
    def _connection(self):
        if self._conn is None:
            self._conn = pymysql.connect(**self._kwargs)
        else:
            try:
                self._conn.ping(reconnect=True)
            except Exception:
                self._conn = pymysql.connect(**self._kwargs)
        return self._conn

    # ---- 查询 ----
    def query(self, sql: str, params: Optional[dict | tuple] = None) -> List[dict]:
        with self._connection().cursor() as cursor:
            cursor.execute(sql, params or ())
            return list(cursor.fetchall())

    def query_one(self, sql: str, params: Optional[dict | tuple] = None) -> Optional[dict]:
        rows = self.query(sql, params)
        return rows[0] if rows else None

    def query_value(self, sql: str, params: Optional[dict | tuple] = None) -> Any:
        """单行单列：SELECT COUNT(*) ... / SELECT stock ..."""
        row = self.query_one(sql, params)
        if row is None:
            return None
        values = list(row.values())
        if len(values) != 1:
            raise ValueError(f"期望单行单列，实际列: {list(row)}")
        return values[0]

    # ---- 写入（造数/状态注入）----
    def execute(self, sql: str, params: Optional[dict | tuple] = None) -> int:
        """UPDATE / DELETE / INSERT，返回受影响行数。"""
        with self._connection().cursor() as cursor:
            affected = cursor.execute(sql, params or ())
            self._conn.commit()
            return affected

    def close(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            finally:
                self._conn = None
