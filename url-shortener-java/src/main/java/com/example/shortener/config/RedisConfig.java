package com.example.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Token bucket rate limiter, loaded once and executed atomically via EVALSHA on every
     * call. Doing the check-and-decrement inside the Lua script (rather than GET then SET
     * from app code) is what makes this race-free under concurrent requests.
     */
    @Bean
    public DefaultRedisScript<List> tokenBucketScript() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new org.springframework.core.io.ClassPathResource("lua/token_bucket.lua"));
        script.setResultType(List.class);
        return script;
    }
}
