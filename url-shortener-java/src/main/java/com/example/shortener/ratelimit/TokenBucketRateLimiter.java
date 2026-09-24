package com.example.shortener.ratelimit;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class TokenBucketRateLimiter {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> tokenBucketScript;
    private final ShortenerProperties properties;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate,
                                   DefaultRedisScript<List> tokenBucketScript,
                                   ShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.tokenBucketScript = tokenBucketScript;
        this.properties = properties;
    }

    public enum LimitClass { CREATE, REDIRECT }

    public record Decision(boolean allowed, long tokensRemaining, long retryAfterMs) {}

    /**
     * @param identity  API key if present, otherwise caller's IP - the caller decides which,
     *                  this class just needs a stable string to bucket on.
     */
    public Decision checkAndConsume(LimitClass limitClass, String identity) {
        ShortenerProperties.RateLimit.Bucket cfg = switch (limitClass) {
            case CREATE -> properties.rateLimit().create();
            case REDIRECT -> properties.rateLimit().redirect();
        };

        String key = "ratelimit:" + limitClass.name().toLowerCase() + ":" + identity;

        @SuppressWarnings("unchecked")
        List<Long> result = redisTemplate.execute(
                tokenBucketScript,
                Collections.singletonList(key),
                String.valueOf(cfg.capacity()),
                String.valueOf(cfg.refillTokens()),
                String.valueOf(cfg.refillPeriodSeconds()),
                String.valueOf(System.currentTimeMillis()),
                "1"
        );

        boolean allowed = result.get(0) == 1L;
        return new Decision(allowed, result.get(1), result.get(2));
    }
}
