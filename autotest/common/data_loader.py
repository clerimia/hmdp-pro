"""yaml → parametrize 数据加载（数据层 data/*.yaml 与用例分离）。

约定：data/<name>.yaml 顶层是 list，每项一条用例数据 dict；
有 id 字段时作为 pytest 参数化 id 显示，失败时能对回 yaml 行。
"""
from __future__ import annotations

from pathlib import Path
from typing import Any, List

import pytest
import yaml

_DATA_DIR = Path(__file__).resolve().parent.parent / "data"


def load_cases(name: str) -> List[dict]:
    """读 data/<name>.yaml，顶层必须是 list（空列表=该链路用例票尚未填充）。"""
    path = _DATA_DIR / f"{name}.yaml"
    if not path.exists():
        raise FileNotFoundError(f"用例数据文件不存在: {path}")
    with open(path, encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if data is None:
        return []
    if not isinstance(data, list):
        raise ValueError(f"{path} 顶层必须是 list，实际是 {type(data).__name__}")
    return data


def parametrized(name: str) -> List[Any]:
    """load_cases + pytest.param 包装（带 id）。直接喂 @pytest.mark.parametrize。"""
    return [
        pytest.param(case, id=str(case.get("id", f"case-{i}")))
        for i, case in enumerate(load_cases(name))
    ]
