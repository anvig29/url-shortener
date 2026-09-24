package com.example.shortener.idgen;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Content-addressed code generation: the same long URL always maps to the same short
 * code. Useful when you want idempotent shortening (a user pasting the same URL twice
 * gets the same link back instead of a new row every time) and for cheap client-side
 * dedup checks (hash the URL, ask "have I seen this hash" before even calling the API).
 *
 * Unlike the snowflake generator, this genuinely can collide: two different URLs can
 * truncate to the same 48-bit prefix. It's handled explicitly here via salted retries
 * (a "linear probing" scheme) rather than pretending it can't happen - the caller
 * (UrlShortenerService) is responsible for detecting a DB-level unique-constraint hit on
 * a *different* long_url and calling generate() again with an incremented salt.
 */
@Component
public class ContentHashCodeGenerator {

    private static final int PREFIX_BITS = 48; // 48 bits -> 8 base62 chars, plenty of headroom
    private static final long MASK = (1L << PREFIX_BITS) - 1;
    private static final int MAX_SALT_ATTEMPTS = 20;

    /**
     * @param longUrl   the URL being shortened
     * @param saltAttempt 0 on first try; caller increments on collision and retries.
     */
    public String generateCode(String longUrl, int saltAttempt) {
        if (saltAttempt >= MAX_SALT_ATTEMPTS) {
            throw new IllegalStateException(
                    "Exceeded max collision-retry attempts (" + MAX_SALT_ATTEMPTS + ") for content hash of URL");
        }
        long prefix = truncatedSha256(longUrl, saltAttempt) & MASK;
        return Base62.encode(prefix);
    }

    /** Full hash, stored alongside the code so we can look up "does this exact URL already have a code". */
    public String fullHashHex(String longUrl) {
        byte[] digest = sha256(longUrl.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private long truncatedSha256(String longUrl, int saltAttempt) {
        String input = saltAttempt == 0 ? longUrl : longUrl + ":" + saltAttempt;
        byte[] digest = sha256(input.getBytes(StandardCharsets.UTF_8));
        // Take the first 8 bytes as an unsigned-ish long (top bit cleared by masking above).
        return ByteBuffer.wrap(digest, 0, 8).getLong();
    }

    private byte[] sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
