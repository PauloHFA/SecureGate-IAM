-- Exact decimal version ordering, event-time windows, idempotent attempts, completion last.
local prior = redis.call('GET', KEYS[1])
if prior and prior ~= ARGV[1] then return -1 end
local action = ARGV[2]
local time = redis.call('TIME')
local now = tonumber(time[1])*1000 + math.floor(tonumber(time[2])/1000)
local occurred = tonumber(ARGV[5])
if occurred > now + 60000 then return -1 end
local function requireType(key, expected)
  local actual = redis.call('TYPE', key).ok
  if actual ~= 'none' and actual ~= expected then error('Invalid projection type') end
end
local function extend(key, deadline)
  local old = redis.call('GET', key)
  if old and not tonumber(old) then error('Invalid deadline') end
  if deadline > now and (not old or deadline > tonumber(old)) then
    redis.call('SET',key,string.format('%.0f',deadline),'PXAT',string.format('%.0f',deadline))
  end
end
if action == 'IDENTITY' or action == 'POLICY' then
  requireType(KEYS[2],'string')
  local current = redis.call('GET',KEYS[2])
  local proposed = ARGV[3]..'|'..ARGV[4]
  if current and (#current < 21 or string.sub(current,20,20) ~= '|' or not string.match(string.sub(current,1,19),'^%d+$')) then
    return redis.error_reply('Invalid version watermark')
  end
  if current and string.sub(current,1,19) == ARGV[3] and current ~= proposed then return -1 end
  if not current or string.sub(current,1,19) < ARGV[3] then redis.call('SET',KEYS[2],proposed) end
elseif action == 'RISK' then
  requireType(KEYS[2],'string')
  extend(KEYS[2],occurred+tonumber(ARGV[11]))
elseif action == 'FAILURE' then
  local window = tonumber(ARGV[9])
  if ARGV[7] == '1' then requireType(KEYS[2],'zset');requireType(KEYS[4],'string') end
  if ARGV[8] == '1' then requireType(KEYS[3],'zset');requireType(KEYS[5],'string') end
  -- Validate both deadline values before the first mutation; errors never mark completion.
  for i=4,5 do
    local value=redis.call('GET',KEYS[i])
    if value and not tonumber(value) then return redis.error_reply('Invalid deadline') end
  end
  local function attempt(counter,block,threshold)
    redis.call('ZREMRANGEBYSCORE',counter,'-inf',now-window)
    if occurred > now-window then redis.call('ZADD',counter,'NX',occurred,ARGV[6]) end
    -- Only the newest threshold attempts are needed; memory is bounded during an attack.
    redis.call('ZREMRANGEBYRANK',counter,0,-threshold-1)
    redis.call('PEXPIRE',counter,window+60000)
    if redis.call('ZCARD',counter) >= threshold then
      local newest=redis.call('ZREVRANGE',counter,0,0,'WITHSCORES')
      extend(block,tonumber(newest[2])+tonumber(ARGV[10]))
    end
  end
  if ARGV[7] == '1' then attempt(KEYS[2],KEYS[4],tonumber(ARGV[12])) end
  if ARGV[8] == '1' then attempt(KEYS[3],KEYS[5],tonumber(ARGV[13])) end
else return redis.error_reply('Invalid projection action') end
redis.call('SET',KEYS[1],ARGV[1],'PX',604800000)
if prior then return 0 else return 1 end
