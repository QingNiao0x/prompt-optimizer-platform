-- 与占用脚本使用相同复制模式，保持服务端时间与租约写入的兼容性。
if redis.replicate_commands then redis.replicate_commands() end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
-- 已过期或释放的令牌不能复活，否则会在新请求占位后超过上限。
if not score or tonumber(score) <= now then
    return 0
end
local ttl = tonumber(ARGV[2])
redis.call('ZADD', KEYS[1], now + ttl, ARGV[1])
redis.call('PEXPIRE', KEYS[1], ttl)
return 1
