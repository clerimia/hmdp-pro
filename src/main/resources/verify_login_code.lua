-- KEYS[1] 验证码，KEYS[2] 错误次数，KEYS[3] 临时锁。
-- ARGV[1] 提交码，ARGV[2] 最大错误次数，ARGV[3] 计数窗口秒数，ARGV[4] 锁定秒数。
if redis.call('exists', KEYS[3]) == 1 then
    return -1
end
local cached = redis.call('get', KEYS[1])
if not cached or cached ~= ARGV[1] then
    local attempts = redis.call('incr', KEYS[2])
    if attempts == 1 then
        redis.call('expire', KEYS[2], ARGV[3])
    end
    if attempts >= tonumber(ARGV[2]) then
        redis.call('set', KEYS[3], '1', 'EX', ARGV[4])
        redis.call('del', KEYS[2])
        return -1
    end
    return 0
end
redis.call('del', KEYS[1])
redis.call('del', KEYS[2])
redis.call('del', KEYS[3])
return 1
