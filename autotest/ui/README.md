# Playwright UI 自动化（预留，本票不展开）

定位见 wayfinder 票「Playwright UI 自动化在三条链路里的定位」（已关）：

- **UI 层测前端行为，不测后端逻辑**——抢券正确性只能 pytest + JMeter 证；
- 范围 = 2 页面（login.html / shop-detail.html）、5~8 条用例；
- 可测性六约束：sessionStorage 注入 token、warmup、redis-py 取码、
  testid（前端不改则跳过）、label 坏点直接点 input、`$message` 即时断言；
- 抢券 UI 冒烟暂缓（抢券策略票 §9.3），恢复时机见 map Not yet specified。

依赖单独装：`pip install -r ui/requirements-ui.txt`（不混主依赖）。
