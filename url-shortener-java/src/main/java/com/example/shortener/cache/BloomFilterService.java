package com.example.shortener.cache;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis-backed Bloom filter over the set of *known-existing* short codes.
 *
 * Why this exists: a scraper/bot enumerating random codes (or just legitimate 404 traffic)
 * would otherwise cause a cache miss AND a Postgres miss on every single request - "cache
 * penetration". The DB gets hammered by lookups for keys that will never exist. A Bloom
 * filter answers "definitely not present" in O(k) memory-only bit checks, before Redis
 * cache or Postgres is touched at all. False positives are inherent to Bloom filters
 * (says "maybe present" for a key that doesn't exist) and are cheap here - they just fall
 * through to a normal cache-then-DB miss, same as if the filter didn't exist. False
 * negatives are impossible by construction, so a real code is never wrongly rejected.
 *
 * Implemented directly on Redis SETBIT/GETBIT with the classic Kirsch-Mitzenmacher
 * double-hashing trick (derive k hash functions from two independent hashes) rather than
 * pulling in RedisBloom module, so this runs on stock Redis/ElastiCache/etc.
 */
@Component
public class BloomFilterService {

    private static final String BLOOM_KEY = "bloom:short_codes";

    private final StringRedisTemplate redisTemplate;
    private final long bitSize;
    private final int numHashFunctions;

    public BloomFilterService(StringRedisTemplate redisTemplate, ShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        long n = properties.cache().bloom().expectedInsertions();
        double p = properties.cache().bloom().falsePositiveRate();
        // Standard optimal-size formulas: m = -(n * ln(p)) / (ln(2)^2), k = (m/n) * ln(2)
        this.bitSize = (long) Math.ceil(-(n * Math.log(p)) / (Math.log(2) * Math.log(2)));
        this.numHashFunctions = Math.max(1, (int) Math.round((double) bitSize / n * Math.log(2)));
    }

    public void add(String code) {
        for (long position : bitPositions(code)) {
            redisTemplate.opsForValue().setBit(BLOOM_KEY, position, true);
        }
    }

    /** true => definitely not present (safe to short-circuit as 404). false => maybe present, must check cache/DB. */
    public boolean definitelyDoesNotExist(String code) {
        for (long position : bitPositions(code)) {
            Boolean bit = redisTemplate.opsForValue().getBit(BLOOM_KEY, position);
            if (bit == null || !bit) {
                return true;
            }
        }
        return false;
    }

    private List<Long> bitPositions(String code) {
        long[] hashes = murmurLikeDoubleHash(code);
        long h1 = hashes[0];
        long h2 = hashes[1];
        List<Long> positions = new ArrayList<>(numHashFunctions);
        for (int i = 0; i < numHashFunctions; i++) {
            long combined = h1 + (long) i * h2;
            long position = Math.floorMod(combined, bitSize);
            positions.add(position);
        }
        return positions;
    }

    /**
     * Two independent 64-bit hashes derived from SHA-256 halves. Not cryptographically
     * necessary here (this is a Bloom filter, not a security boundary) but SHA-256 is
     * already a dependency-free JDK primitive and gives us good bit dispersion for free
     * instead of hand-rolling a weaker hash.
     */
    private long[] murmurLikeDoubleHash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            long h1 = bytesToLong(digest, 0);
            long h2 = bytesToLong(digest, 8);
            return new long[] { h1, h2 };
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private long bytesToLong(byte[] bytes, int offset) {
        long result = 0;
        for (int i = 0; i < 8; i++) {
            result = (result << 8) | (bytes[offset + i] & 0xFF);
        }
        return result & Long.MAX_VALUE; // keep non-negative for clean modulo
    }
}
