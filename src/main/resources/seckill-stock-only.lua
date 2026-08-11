-- 对比测试用：只扣库存，不做一人一单（模拟无 Redis 用户去重）
local voucherId = ARGV[1]
local orderId = ARGV[3]
local stockKey = 'seckill:stock:' .. voucherId
local stock = tonumber(redis.call('get', stockKey))
if stock == nil or stock <= 0 then
    return 1
end
redis.call('incrby', stockKey, -1)
redis.call('set', 'seckill:txn:' .. orderId, '1', 'EX', 3600)
return 0
