-- 固定窗口计数。
-- INCR 与 EXPIRE 必须在一次原子执行里完成：分两条命令时，若在两者之间崩溃/超时，
-- key 会永远没有 TTL，这个桶只增不减，最终把接口永久限死。
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('EXPIRE', KEYS[1], ARGV[1])
end
return current
