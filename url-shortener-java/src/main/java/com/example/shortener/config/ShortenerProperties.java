package com.example.shortener.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "shortener")
public record ShortenerProperties(
        String baseUrl,
        Snowflake snowflake,
        Cache cache,
        RateLimit rateLimit,
        Analytics analytics
) {
    public record Snowflake(long workerId) {}

    public record Cache(long ttlSeconds, Bloom bloom) {
        public record Bloom(long expectedInsertions, double falsePositiveRate) {}
    }

    public record RateLimit(Bucket create, Bucket redirect) {
        public record Bucket(long capacity, long refillTokens, long refillPeriodSeconds) {}
    }

    public record Analytics(
            String streamKey,
            String consumerGroup,
            int batchSize,
            long batchTimeoutMs,
            int consumerThreads
    ) {}
}
