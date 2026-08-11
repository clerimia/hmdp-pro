-- 网关令牌桶（懒补充）：全局共一桶，限制进入 Java 的总 QPS（挡洪峰）
-- 多 worker 下用 shared.dict 短锁保证读写原子
local _M = {}

local function now_ms()
    return ngx.now() * 1000
end

local function lock(dict, lock_key)
    local i = 0
    while not dict:add(lock_key, 1, 1) do
        ngx.sleep(0.001)
        i = i + 1
        if i > 50 then
            return false
        end
    end
    return true
end

local function unlock(dict, lock_key)
    dict:delete(lock_key)
end

---
-- dict: ngx.shared dict
-- key_prefix: 如 "seckill"
-- rate: 每秒补充令牌数（全局）
-- capacity: 桶容量（全局突发）
-- @return true 放行 / false 限流
function _M.allow(dict, key_prefix, rate, capacity)
    local tokens_key = key_prefix .. ":global:t"
    local refill_key = key_prefix .. ":global:r"
    local lock_key = key_prefix .. ":global:lock"

    if not lock(dict, lock_key) then
        -- 抢锁失败：保守拒绝，避免无锁超卖令牌
        return false
    end

    local ok, allowed = pcall(function()
        local now = now_ms()
        local tokens = tonumber(dict:get(tokens_key))
        local last = tonumber(dict:get(refill_key))
        if tokens == nil then
            tokens = capacity
            last = now
        end

        local elapsed = now - last
        if elapsed > 0 then
            local refill = math.floor(elapsed / 1000 * rate)
            if refill > 0 then
                tokens = math.min(capacity, tokens + refill)
                last = last + refill * 1000
            end
        end

        if tokens < 1 then
            dict:set(tokens_key, tokens)
            dict:set(refill_key, last)
            return false
        end

        tokens = tokens - 1
        dict:set(tokens_key, tokens)
        dict:set(refill_key, last)
        return true
    end)

    unlock(dict, lock_key)

    if not ok then
        ngx.log(ngx.ERR, "token_bucket error: ", allowed)
        return false
    end
    return allowed
end

return _M
