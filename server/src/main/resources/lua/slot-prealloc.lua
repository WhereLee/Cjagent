-- 格口预扣（原子）：按偏好顺序从第一个非空空闲集合里 SPOP 出一个格口
--
-- KEYS[1..n] = cab:alloc:free:{cabinetId}:{SIZE}（集合成员 = 空闲格口 ID，由 DB 同步而来）
-- KEYS[n+1]  = cab:alloc:hold:{orderNo}（本次占位的租约标记，hash + TTL）
-- ARGV[1]    = n（候选集合个数）
-- ARGV[2]    = 租约秒数
-- ARGV[3]    = 客户 ID
--
-- 返回："序号:格口ID"，例如 "2:8812"；nil 表示全部无空闲
--
-- 为什么用集合而不是计数：计数的两个致命漂移——重复归还凭空造出可卖位（等于超卖），
-- 崩溃/漏消费让计数长期偏低（少卖）。集合成员只能由 DB 真相同步，多卖在结构上做不到。
-- 为什么用 SPOP 而不是 SMEMBERS + SREM：SPOP 单命令原子弹出，两个请求不可能拿到同一个格口。
-- 为什么 hold 里要存来源 key 与客户 ID：
--   · 存 key：取消/归还时能精确放回**当初那个**集合，不必让调用方重新推导尺寸；
--   · 存客户 ID：排队中的单在 DB 里还没有行，无法靠行做归属校验——
--     没有它，"取消一张还没落库的单"就只能不看是谁在取消（越权取消别人的占位）。
local n = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
local hold = KEYS[n + 1]

for i = 1, n do
  local slot = redis.call('SPOP', KEYS[i])
  if slot then
    redis.call('HSET', hold, 'idx', i, 'slot', slot, 'key', KEYS[i], 'cust', ARGV[3])
    redis.call('EXPIRE', hold, ttl)
    return tostring(i) .. ':' .. slot
  end
end

return nil
