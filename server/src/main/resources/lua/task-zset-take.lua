-- 原子取出到期的任务提醒（ZSet 版"抢占"）
--
-- KEYS[1] = cab:task:zset
-- ARGV[1] = 当前时间（毫秒，作为分数上界）
-- ARGV[2] = 最多取多少条
--
-- 返回：取出的成员列表（已从 ZSet 删除）
--
-- 为什么必须写成一个脚本：ZRANGEBYSCORE + ZREM 是"读—改—删"两步，
-- 两个 worker 并发时会各自取到同一批成员（提醒可以重，但重复会放大成无谓的 DB 抢占与日志）。
-- 脚本内单线程执行，取出即删除，天然互斥。
--
-- 注意：这里删掉成员不等于任务被消费——真正的执行权在 DB 的租约上。
-- 若脚本取出后进程崩溃，该提醒丢失，任务仍会被兜底轮询扫到（提醒是加速器，轮询是保底）。
local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2]))
if #due == 0 then
  return {}
end

redis.call('ZREM', KEYS[1], unpack(due))
return due
