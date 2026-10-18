-- B05 预占 Lua（两 key 收敛版）：仅 ticket:stock 与 ticket:occupied 两个 key。
--
-- KEYS[1] = ticket:stock:<id>     String 可售库存（严格非负十进制整数，边界 0..2147483647）
-- KEYS[2] = ticket:occupied:<id>  ZSet   member=userId, score=首次预占毫秒时间；重复请求不刷新
-- ARGV[1] = userId
-- ARGV[2] = nowMillis             （服务端生成，毫秒）
--
-- 返回整数：0=RESERVED 1=OCCUPIED 2=SOLD_OUT 3=NOT_READY
--
-- 校验顺序：key 类型 -> 库存字符串严格格式 -> 时间值 -> 用户占用(优先) -> 库存。
-- 全部校验都在写操作(DECRBY/ZADD)之前：Lua 原子执行不自动回滚运行错误，
-- 因此绝不能先用 tonumber('1e2') 之类宽松解析通过校验，再让 DECRBY 运行期报错。

local stockKey, occupiedKey = KEYS[1], KEYS[2]
local userId, nowMillis = ARGV[1], ARGV[2]

-- 1) key 类型校验（缺失的 ZSet 即空，是正常情况）
if redis.call('TYPE', stockKey).ok ~= 'string' then
    return 3 -- NOT_READY
end
local occupiedType = redis.call('TYPE', occupiedKey).ok
if occupiedType ~= 'none' and occupiedType ~= 'zset' then
    return 3 -- NOT_READY
end

-- 2) 库存严格十进制整数：只允许 "0" 或 "1..9" 开头数字串，拒绝科学计数/浮点/前导零/负数
local stockText = redis.call('GET', stockKey)
local stock
if stockText == '0' then
    stock = 0
elseif stockText ~= nil and stockText:match('^[1-9]%d*$') then
    stock = tonumber(stockText)
else
    return 3 -- NOT_READY
end
if stock == nil or stock < 0 or stock > 2147483647 then
    return 3 -- NOT_READY
end

-- 3) 时间值校验：必须是非有限 NaN/Inf 之外、可解析为整数的毫秒值，
--    否则会在扣库存之后 ZADD 失败造成部分写入
local score = tonumber(nowMillis)
if score == nil or score ~= score or score == math.huge or score == -math.huge or score % 1 ~= 0 then
    return 3 -- NOT_READY
end

-- 4) 已占用优先于售罄：买过的用户可找回订单
if redis.call('ZSCORE', occupiedKey, userId) ~= false then
    return 1 -- OCCUPIED
end

-- 5) 最后判库存：无库存直接拒绝，不写任何用户记录
if stock == 0 then
    return 2 -- SOLD_OUT
end

-- 6) 同一原子：库存减一 + ZADD NX 登记（NX 保证重复请求不刷新 score）
redis.call('DECRBY', stockKey, 1)
redis.call('ZADD', occupiedKey, 'NX', nowMillis, userId)
return 0 -- RESERVED