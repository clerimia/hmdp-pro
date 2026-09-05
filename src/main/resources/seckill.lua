-- 1.参数列表
-- 1.1.优惠券id
local voucherId = ARGV[1]
-- 1.2.用户id
local userId = ARGV[2]
-- 1.3.订单id
local orderId = ARGV[3]

-- 2.数据key
-- 2.1.库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2.订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.脚本业务
-- 3.1.判断库存是否充足 get stockKey（key 不存在视为无库存，避免 tonumber(nil) 报错）
local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    -- 3.2.库存不足，返回1
    return 1
end
-- 3.2.判断用户是否领取 SISMEMBER orderKey userId
if(redis.call('sismember', orderKey, userId) == 1) then
    -- 3.3.存在，说明是重复领取，返回2
    return 2
end
-- 3.4.扣库存 incrby stockKey -1
redis.call('incrby', stockKey, -1)
-- 3.5.领取（保存用户）sadd orderKey userId
redis.call('sadd', orderKey, userId)
-- 3.6.认领映射：记录该用户领到的原始 orderId，供对账补单复用订单号
--     （换号补单会让用户轮询旧单号永远 NOT_FOUND——「领到了但订单消失」）。
--     TTL 1209600s = 14d（对应 RedisConstants.SECKILL_CLAIM_TTL_SECONDS），每次写滑动续期。
--     注意：stockKey/orderKey 绝不能加 TTL——长活动静默超期后 key 过期，warmUpStock 的
--     beginMillis 守卫会 fail-closed 拒绝回填，活动将永久「库存不足」；key 生命周期
--     只由对账任务对已结束券统一续期管理。
redis.call('hset', 'seckill:claim:' .. voucherId, userId, orderId)
redis.call('expire', 'seckill:claim:' .. voucherId, 1209600)
-- 3.7.写事务标记：与扣库存同脚本原子；供 RocketMQ 事务回查（TTL 1 小时）
redis.call('set', 'seckill:txn:' .. orderId, '1', 'EX', 3600)
-- 3.8.返回0：校验通过，由事务消息 COMMIT 后投递，异步落库
return 0