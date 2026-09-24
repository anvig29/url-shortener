package com.example.shortener.service;

import com.example.shortener.cache.UrlCacheService;
import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.idgen.Base62;
import com.example.shortener.idgen.ContentHashCodeGenerator;
import com.example.shortener.idgen.SnowflakeIdGenerator;
import com.example.shortener.repository.ShortUrlRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class UrlShortenerService {

    private static final int MAX_CONTENT_HASH_RETRIES = 5;

    private final ShortUrlRepository repository;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final ContentHashCodeGenerator contentHashCodeGenerator;
    private final UrlCacheService cacheService;
    private final ShortenerProperties properties;

    public UrlShortenerService(ShortUrlRepository repository,
                                SnowflakeIdGenerator snowflakeIdGenerator,
                                ContentHashCodeGenerator contentHashCodeGenerator,
                                UrlCacheService cacheService,
                                ShortenerProperties properties) {
        this.repository = repository;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.contentHashCodeGenerator = contentHashCodeGenerator;
        this.cacheService = cacheService;
        this.properties = properties;
    }

    public ShortUrl createSnowflake(String longUrl, String apiKey) {
        // Structurally collision-free (see SnowflakeIdGenerator javadoc), so under correct
        // configuration this never retries. One defensive retry guards against the one way
        // it *could* still collide in practice: a misconfigured duplicate worker-id across
        // instances. That's a deployment bug, not a design assumption, so we log loudly
        // rather than silently looping forever.
        for (int attempt = 0; attempt < 2; attempt++) {
            long id = snowflakeIdGenerator.nextId();
            String code = Base62.encode(id);
            try {
                ShortUrl shortUrl = new ShortUrl(id, code, longUrl, ShortUrl.HashMode.SNOWFLAKE, null, apiKey, null);
                shortUrl = repository.save(shortUrl);
                cacheService.populate(shortUrl);
                return shortUrl;
            } catch (DataIntegrityViolationException e) {
                if (attempt == 0) {
                    continue; // one retry; see javadoc above
                }
                throw new IllegalStateException(
                        "Snowflake code collision on retry - check for duplicate worker-id across instances", e);
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * Idempotent creation: same longUrl always yields the same code. Handles the two ways
     * this can race/collide under concurrency:
     *  - Two callers shorten the same URL concurrently -> both miss the initial lookup,
     *    both try to insert -> the content_hash unique index rejects the second insert ->
     *    we catch it and return the row the other transaction just committed.
     *  - Two *different* URLs truncate to the same 48-bit code prefix -> the code unique
     *    index rejects the insert -> we retry with an incremented salt (see
     *    ContentHashCodeGenerator) rather than treating it as a hard failure.
     */
    @Transactional
    public ShortUrl createContentHash(String longUrl, String apiKey) {
        String fullHash = contentHashCodeGenerator.fullHashHex(longUrl);

        Optional<ShortUrl> existing = repository.findByContentHash(fullHash);
        if (existing.isPresent()) {
            return existing.get();
        }

        for (int saltAttempt = 0; saltAttempt < MAX_CONTENT_HASH_RETRIES; saltAttempt++) {
            String code = contentHashCodeGenerator.generateCode(longUrl, saltAttempt);
            long id = snowflakeIdGenerator.nextId(); // still need a PK; snowflake is fine for that
            try {
                ShortUrl shortUrl = new ShortUrl(id, code, longUrl, ShortUrl.HashMode.CONTENT_HASH, fullHash, apiKey, null);
                shortUrl = repository.save(shortUrl);
                cacheService.populate(shortUrl);
                return shortUrl;
            } catch (DataIntegrityViolationException e) {
                // Could be a code collision (different URL, same prefix) or a content_hash
                // race (same URL, concurrent insert already won). Distinguish by re-querying.
                Optional<ShortUrl> raceWinner = repository.findByContentHash(fullHash);
                if (raceWinner.isPresent()) {
                    return raceWinner.get();
                }
                // Otherwise it was a code collision against a different URL - loop and retry
                // with the next salt.
            }
        }
        throw new IllegalStateException(
                "Exceeded content-hash collision retries for URL - extremely unlikely; check PREFIX_BITS sizing");
    }
}
