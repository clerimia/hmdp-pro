-- 方案 B 消费者：Redis 库存 + 一人一单（幂等：已 claim 则返回 0，便于 MQ 重试续跑落库）
-- ARGV: voucherId, userId
-- 返回: 0 可落库；1 库存不足
local voucherId = ARGV[1]
local userId = ARGV[2]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 已 claim：说明上次可能扣了 Redis 但 SQL 未成功，允许继续落库
if redis.call('sismember', orderKey, userId) == 1 then
    return 0
end

local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
return 0
