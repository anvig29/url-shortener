-- Atomic token-bucket rate limiter.
--
-- KEYS[1]  = bucket key, e.g. "ratelimit:create:apikey:abc123"
-- ARGV[1]  = capacity (max tokens the bucket can hold)
-- ARGV[2]  = refill_tokens (tokens added per refill_period)
-- ARGV[3]  = refill_period_seconds
-- ARGV[4]  = now (unix millis, passed in from app so we don't depend on Redis TIME across
--            replicas/clusters behaving identically - deterministic input, deterministic script)
-- ARGV[5]  = tokens requested (usually 1)
--
-- Returns: { allowed (1/0), tokens_remaining, retry_after_ms }
--
-- This whole check-and-decrement happens as one atomic operation inside Redis, so there is
-- no race window between "read remaining tokens" and "decrement" the way there would be if
-- this logic lived in application code across two separate round trips.

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refill_tokens = tonumber(ARGV[2])
local refill_period_ms = tonumber(ARGV[3]) * 1000
local now = tonumber(ARGV[4])
local requested = tonumber(ARGV[5])

local bucket = redis.call("HMGET", key, "tokens", "last_refill")
local tokens = tonumber(bucket[1])
local last_refill = tonumber(bucket[2])

if tokens == nil then
    tokens = capacity
    last_refill = now
end

-- Refill proportionally to elapsed time (not just "one bucket-size every period" - this
-- lets bursts recover smoothly instead of in discrete jumps).
local elapsed = now - last_refill
if elapsed > 0 then
    local refill_rate_per_ms = refill_tokens / refill_period_ms
    local refill_amount = elapsed * refill_rate_per_ms
    tokens = math.min(capacity, tokens + refill_amount)
    last_refill = now
end

local allowed = 0
local retry_after_ms = 0

if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
else
    local deficit = requested - tokens
    retry_after_ms = math.ceil(deficit / (refill_tokens / refill_period_ms))
end

redis.call("HMSET", key, "tokens", tostring(tokens), "last_refill", tostring(last_refill))
-- Bucket is idle-expired well past its refill period so we don't leak keys for one-off callers.
redis.call("PEXPIRE", key, refill_period_ms * 10)

return { allowed, math.floor(tokens), retry_after_ms }
