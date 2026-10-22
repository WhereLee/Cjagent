-- 格口预占（原子）：按偏好顺序从第一个非空空闲集合里 SPOP 出一个格口
--
-- KEYS[1..n] = cab:alloc:free:{cabinetId}:{SIZE}（集合成员 = 空闲格口 ID，由 DB 同步而来）
-- ARGV[1]    = n（候选集合个数）
--
-- 返回："序号:格口ID"，例如 "2:8812"；nil 表示全部无空闲
--
-- 为什么用集合而不是计数：计数的两个致命漂移——重复归还凭空造出可卖位（等于超卖），
-- 崩溃让计数长期偏低（少卖）。集合成员只能由 DB 真相同步，多卖在结构上做不到。
-- 为什么用 SPOP 而不是 SMEMBERS + SREM：SPOP 单命令原子弹出，两个请求不可能拿到同一个格口。
-- 为什么要脚本而不是在 Java 里循环 SPOP：一次往返试完全部尺寸档位。多次往返不仅慢，
-- 还会在档位之间插进别的请求，让"最小满足优先"退化成"看谁运气好"。
local n = tonumber(ARGV[1])

for i = 1, n do
  local slot = redis.call('SPOP', KEYS[i])
  if slot then
    return tostring(i) .. ':' .. slot
  end
end

return nil
