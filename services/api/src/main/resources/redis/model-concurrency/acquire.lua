-- 检查和占用必须在同一脚本中执行，防止多个实例同时越过容量边界。
-- 本地 Redis 3.2 需先启用命令效果复制才能在 TIME 后写入；Redis 7 已默认采用该模式。
if redis.replicate_commands then redis.replicate_commands() end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local ttl = tonumber(ARGV[3])
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[2]) then
    return 0
end
redis.call('ZADD', KEYS[1], now + ttl, ARGV[1])
redis.call('PEXPIRE', KEYS[1], ttl)
return 1
