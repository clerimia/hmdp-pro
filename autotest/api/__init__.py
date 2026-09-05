"""api 层：1 个接口 1 个函数，只发请求。

铁律（框架结构票 §1）：函数里只有一次 HTTP 调用，不 assert、不 sleep、不重试。
唯一例外是协议级断言（非 JSON 响应抛 ApiProtocolError，由 client._parse 负责）。
业务结论一律在 case 层用断言助手表达。

重试豁免说明：AuthedClient（ctx.client）对 401 的「失效重登 + 原请求重试一次」是
规格 §2 token 缓存被动失效路径的载体——那是传输层会话自愈，不是业务重试；
api 函数本体（用裸 http 调用）仍然只发一次请求。

接口路径与鉴权边界已对源码核实（2026-09-05）：
- 免登录（MvcConfig excludePathPatterns）：/shop/**、/voucher/**、/user/code、/user/login；
- 需登录：/user/me、/voucher-order/**（无 authorization 头 → 401 空 body）；
- 限流只挂 /voucher-order/seckill/{id}（5 次/秒/userId）与 /seckill/result/{orderId}
  （10 次/秒/userId），两个桶独立。
"""
