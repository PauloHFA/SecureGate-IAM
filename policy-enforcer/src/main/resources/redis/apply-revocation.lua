-- Single Redis hash slot. Effects first, completion last; replay never shortens a tombstone.
local previous = redis.call('GET', KEYS[1])
if previous then
  if previous ~= ARGV[1] then return -1 end
end
local current = redis.call('GET', KEYS[2])
local deadline = tonumber(ARGV[2])
if current and not tonumber(current) then return redis.error_reply('Invalid revocation state') end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
if deadline == 0 then
  redis.call('SET', KEYS[2], '0')
elseif deadline > now and (not current or (tonumber(current) ~= 0 and deadline > tonumber(current))) then
  redis.call('SET', KEYS[2], ARGV[2], 'PXAT', ARGV[2])
end
local dedupUntil = math.max(now + 604800000, deadline)
redis.call('SET', KEYS[1], ARGV[1], 'PXAT', string.format('%.0f', dedupUntil))
if previous then return 0 else return 1 end
