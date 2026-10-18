-- B05 精准释放 Lua（两 key 收敛版）：仅 ticket:stock 与 ticket:occupied 两个 key。
--
-- KEYS[1] = ticket:stock:<id>     String 可售库存（严格非负十进制整数，边界 0..2147483647）
-- KEYS[2] = ticket:occupied:<id>  ZSet   member=userId
-- ARGV[1] = userId
--
-- 返回整数：0=RELEASED(实际移除成员并归还1份) 1=NOOP(成员不存在) 2=数据异常(需人工维护)
--
-- 语义：
--   * 仅当 ZREM 实际移除 1 个成员时才 INCR 库存；成员不存在 → NOOP；
--   * 库存缺失/非法/错误类型/加1越界 → 数据异常，绝不无条件归还或创建库存；
--   * 所有校验与越界检查都在写操作(ZREM/INCR)之前。
--
-- 限制（README/组件注释/测试均有说明）：本脚本只防“没有再次占用时的重复释放”，
-- 不防“重购后的旧释放”。两 key 无法区分占用身份，重购后对旧动作重放会误删新占用，
-- 必须由 B 段保证不按旧调用路径第二次释放。

local stockKey, occupiedKey = KEYS[1], KEYS[2]
local userId = ARGV[1]

-- 1) key 类型校验
if redis.call('TYPE', stockKey).ok ~= 'string' then
    return 2 -- 数据异常
end
local occupiedType = redis.call('TYPE', occupiedKey).ok
if occupiedType ~= 'none' and occupiedType ~= 'zset' then
    return 2 -- 数据异常
end

-- 2) 库存严格十进制整数校验（同 order-reserve.lua，禁用宽松 tonumber 路径）
local stockText = redis.call('GET', stockKey)
local stock
if stockText == '0' then
    stock = 0
elseif stockText ~= nil and stockText:match('^[1-9]%d*$') then
    stock = tonumber(stockText)
else
    return 2 -- 数据异常
end
if stock == nil or stock < 0 or stock > 2147483647 then
    return 2 -- 数据异常
end

-- 3) 加 1 越界必须在写操作前检查，避免 ZREM 成功而 INCR 越界造成部分写
if stock >= 2147483647 then
    return 2 -- 数据异常
end

-- 4) 仅当 ZREM 实际移除 1 个成员才归还库存；成员不存在（含空集合）→ NOOP
if redis.call('ZREM', occupiedKey, userId) ~= 1 then
    return 1 -- NOOP
end
redis.call('INCR', stockKey)
return 0 -- RELEASED