package com.example.shortener.cache;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cache-aside lookup for code -> long URL, with two defenses layered in front of Postgres:
 *
 * 1. Bloom filter negative-lookup (see BloomFilterService) - kills cache penetration from
 *    codes that were never created.
 * 2. Singleflight / request coalescing - if a link goes viral and N concurrent requests
 *    miss the Redis cache in the same instant, only ONE of them queries Postgres; the rest
 *    await that same in-flight future. Without this, a cache miss on a hot key becomes a
 *    thundering herd hitting the DB simultaneously.
 *
 * Redis itself is configured with maxmemory-policy=allkeys-lru (see docker-compose /
 * README), so eviction under memory pressure naturally favors keeping hot links cached
 * without any manual LRU bookkeeping here.
 */
@Component
public class UrlCacheService {

    private static final String CACHE_PREFIX = "cache:url:";
    // Sentinel stored for negative caching, so a legitimate 404 (bloom filter false positive,
    // or expired link) doesn't repeatedly hit Postgres either.
    private static final String NEGATIVE_SENTINEL = "\u0000NOT_FOUND\u0000";

    private final StringRedisTemplate redisTemplate;
    private final BloomFilterService bloomFilter;
    private final ShortUrlRepository repository;
    private final Duration ttl;

    // In-flight DB lookups keyed by code, for singleflight coalescing. Cleared as soon as
    // the lookup completes so it never grows unbounded.
    private final ConcurrentHashMap<String, CompletableFuture<Optional<ShortUrl>>> inFlight =
            new ConcurrentHashMap<>();

    public UrlCacheService(StringRedisTemplate redisTemplate,
                            BloomFilterService bloomFilter,
                            ShortUrlRepository repository,
                            ShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.bloomFilter = bloomFilter;
        this.repository = repository;
        this.ttl = Duration.ofSeconds(properties.cache().ttlSeconds());
    }

    public Optional<ShortUrl> lookup(String code) {
        if (bloomFilter.definitelyDoesNotExist(code)) {
            return Optional.empty();
        }

        String cacheKey = CACHE_PREFIX + code;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return NEGATIVE_SENTINEL.equals(cached) ? Optional.empty() : Optional.of(deserialize(code, cached));
        }

        return coalescedDbLookup(code, cacheKey);
    }

    private Optional<ShortUrl> coalescedDbLookup(String code, String cacheKey) {
        CompletableFuture<Optional<ShortUrl>> future = inFlight.computeIfAbsent(code, c ->
                CompletableFuture.supplyAsync(() -> fetchAndCache(c, cacheKey))
        );
        try {
            return future.join();
        } finally {
            // Only the thread that owns this future's completion should ever remove it, but
            // computeIfAbsent + remove-after-join is safe here: worst case is a redundant
            // recomputation on a rare race, never a stale/incorrect result.
            inFlight.remove(code, future);
        }
    }

    private Optional<ShortUrl> fetchAndCache(String code, String cacheKey) {
        Optional<ShortUrl> result = repository.findByCode(code)
                .filter(ShortUrl::isActive);

        if (result.isPresent()) {
            redisTemplate.opsForValue().set(cacheKey, result.get().getLongUrl(), ttl);
        } else {
            // Short negative TTL - keeps a burst of repeated misses off Postgres without
            // permanently caching a "not found" for a code that might get created moments
            // later under content-hash mode.
            redisTemplate.opsForValue().set(cacheKey, NEGATIVE_SENTINEL, Duration.ofSeconds(30));
        }
        return result;
    }

    public void populate(ShortUrl shortUrl) {
        redisTemplate.opsForValue().set(CACHE_PREFIX + shortUrl.getCode(), shortUrl.getLongUrl(), ttl);
        bloomFilter.add(shortUrl.getCode());
    }

    private ShortUrl deserialize(String code, String longUrl) {
        // Cache only ever needs code->longUrl for the redirect hot path; reconstruct a
        // minimal, non-persistent ShortUrl-shaped view rather than round-tripping the DB.
        return new ShortUrl(null, code, longUrl, ShortUrl.HashMode.SNOWFLAKE, null, null, null);
    }
}
