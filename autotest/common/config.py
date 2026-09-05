"""配置门面：三层覆盖，职责分开。

    env.<profile>.yaml  (入库，放地址/端口/默认账号)
            ↓ 被覆盖
    环境变量 HMDP_*      (不入库，放密码等敏感项 + CI 临时改写，yaml 里 ${VAR:-default} 引用)
            ↓ 被覆盖
    命令行 --env / --base-url

关键决策（框架结构票 §3）：pytest.ini 只管 pytest 自己，业务配置另放 yaml——
ini 没有嵌套结构、没有环境变量插值，连接串塞进去很快失控。
"""
from __future__ import annotations

import copy
import os
import re
from pathlib import Path
from typing import Any

import yaml

# ${VAR} 或 ${VAR:-default}
_ENV_PATTERN = re.compile(r"\$\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?\}")

_CONFIG_DIR = Path(__file__).resolve().parent.parent / "config"


def _interpolate(value: Any) -> Any:
    """递归解析 yaml 值里的 ${VAR:-default} 引用，取值顺序：环境变量 > default。"""
    if isinstance(value, str):
        def _sub(match: re.Match) -> str:
            var, default = match.group(1), match.group(2)
            env = os.environ.get(var)
            if env is not None and env != "":
                return env
            return default if default is not None else ""
        return _ENV_PATTERN.sub(_sub, value)
    if isinstance(value, dict):
        return {k: _interpolate(v) for k, v in value.items()}
    if isinstance(value, list):
        return [_interpolate(v) for v in value]
    return value


def _deep_merge(base: dict, override: dict) -> dict:
    """override 逐层覆盖 base（dict 递归合并，其余类型整体替换）。"""
    merged = copy.deepcopy(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = copy.deepcopy(value)
    return merged


class Config:
    """配置门面。属性访问：cfg.base_url / cfg.mysql["port"] / cfg.http_timeout 均可。

    用例里禁止出现任何硬编码地址，一律 cfg.xxx。
    """

    def __init__(self, profile: str = "local", base_url_override: str | None = None):
        self.profile = profile
        with open(_CONFIG_DIR / "config.yaml", encoding="utf-8") as f:
            base = yaml.safe_load(f) or {}
        env_path = _CONFIG_DIR / f"env.{profile}.yaml"
        if not env_path.exists():
            raise FileNotFoundError(f"环境配置不存在: {env_path}")
        with open(env_path, encoding="utf-8") as f:
            env = yaml.safe_load(f) or {}
        data = _deep_merge(base, env)
        data = _interpolate(data)
        if base_url_override:
            data["base_url"] = base_url_override
        self._data = data

    # ---- 访问 ----
    def __getattr__(self, name: str) -> Any:
        # 仅在常规属性查找失败后触发（__getattr__ 语义）
        data = object.__getattribute__(self, "_data")
        if name.startswith("_"):
            raise AttributeError(name)
        if name in data:
            value = data[name]
            return Config._wrap(value)
        raise AttributeError(f"配置项不存在: {name!r}（现有键: {sorted(data)}）")

    def get(self, name: str, default: Any = None) -> Any:
        return self._wrap(self._data.get(name, default))

    @staticmethod
    def _wrap(value: Any) -> Any:
        return Config._LeafDict(value) if isinstance(value, dict) and not isinstance(value, Config._LeafDict) else value

    class _LeafDict(dict):
        """嵌套 dict 的点访问叶子：cfg.mysql.host 也行，cfg.mysql["host"] 也行。"""

        def __getattr__(self, name: str):
            try:
                value = self[name]
            except KeyError as exc:
                raise AttributeError(f"配置项不存在: {name!r}（现有键: {sorted(self)}）") from exc
            return Config._wrap(value)


def load_config(profile: str = "local", base_url_override: str | None = None) -> Config:
    return Config(profile, base_url_override)
