-- 预扣的两种收尾：提交（落库成功）或归还（失败/取消）
--
-- KEYS[1] = cab:alloc:hold:{orderNo}
-- KEYS[2] = cab:alloc:free:{cabinetId}:{SIZE}（当初命中的那个尺寸集合）
-- ARGV[1] = COMMIT | RELEASE
-- ARGV[2] = 格口 ID
--
-- 返回：1 处理完成；0 标记已不存在（重复调用或从未占位）
--
-- 为什么两种收尾都要先判标记存在：COMMIT 与 RELEASE 都可能被重放（消费重试、取消与超时并发），
-- 判标记保证"归还只做一次"——重复 SADD 会让同一格口再次进入空闲集合而被第二次卖掉。
-- COMMIT 只删标记不 SADD：格口已在 DB 里被置为 RESERVED，它不该再是空闲成员。
if redis.call('EXISTS', KEYS[1]) == 0 then
  return 0
end

redis.call('DEL', KEYS[1])

if ARGV[1] == 'RELEASE' then
  redis.call('SADD', KEYS[2], ARGV[2])
end

return 1
