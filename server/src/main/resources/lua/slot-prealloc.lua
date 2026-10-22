-- 格口预占（原子）：按偏好顺序从第一个非空空闲集合里 SPOP 出一个格口
--
-- KEYS[1..n] = cab:alloc:free:{cabinetId}:{SIZE}（集合成员 = 空闲格口 ID，由 DB 同步而来）
-- KEYS[n+1]  = cab:alloc:hold:{holdKey}（本次占位的租约标记，hash + TTL）
-- ARGV[1]    = n（候选集合个数）
-- ARGV[2]    = 租约秒数
--
-- 返回："序号:格口ID"，例如 "2:8812"；nil 表示全部无空闲
--
-- 为什么用集合而不是计数：计数的两个致命漂移——重复归还凭空造出可卖位（等于超卖），
-- 崩溃让计数长期偏低（少卖）。集合成员只能由 DB 真相同步，多卖在结构上做不到。
-- 为什么用 SPOP 而不是 SMEMBERS + SREM：SPOP 单命令原子弹出，两个请求不可能拿到同一个格口。
-- 为什么 hold 里要存来源 key：归还时能精确放回**当初那个**集合，不必让调用方重新推导尺寸。
-- 为什么不再存客户 ID：它原本是给"取消一张还没落库的单"做归属校验用的（那时 DB 里没有行，
-- 只能靠标记里存的客户 ID 判断是谁在取消）。预占与落库现在在同一个请求内完成，
-- 订单行立即存在，归属由 DB 行回答——标记里再存一份就是第二个真相。
local n = tonumber(ARGV[1])
local ttl = tonumber(ARGV[2])
local hold = KEYS[n + 1]

for i = 1, n do
  local slot = redis.call('SPOP', KEYS[i])
  if slot then
    redis.call('HSET', hold, 'idx', i, 'slot', slot, 'key', KEYS[i])
    redis.call('EXPIRE', hold, ttl)
    return tostring(i) .. ':' .. slot
  end
end

return nil
