# URL Shortener — Spring Boot + Postgres + Redis

Implements the design in `ARCHITECTURE.md`: dual-mode custom hashing (Snowflake+base62 /
content-hash dedup), an async click-analytics pipeline (Redis Streams → consumer group →
Postgres raw + rollup tables, with UTM + bot tagging), a cache-aside + bloom-filter +
singleflight caching layer, and an atomic Redis-Lua token-bucket rate limiter.

## Layout

```
src/main/java/com/example/shortener/
  idgen/         Base62, SnowflakeIdGenerator, ContentHashCodeGenerator
  domain/        ShortUrl entity
  repository/    ShortUrlRepository (Spring Data JPA)
  cache/         BloomFilterService, UrlCacheService (cache-aside + singleflight)
  ratelimit/     TokenBucketRateLimiter (wraps lua/token_bucket.lua)
  filter/        RateLimitFilter (servlet filter, gates create vs redirect independently)
  analytics/     ClickEventProducer/Consumer, UserAgentInspector, UtmParams,
                 RollupService, PartitionMaintenanceJob
  service/       UrlShortenerService (create), RedirectService (read path)
  web/           ShortenController, RedirectController, AnalyticsController
  exception/     GlobalExceptionHandler
src/main/resources/
  application.yml
  db/migration/V1__init.sql   (Flyway: short_urls, partitioned click_events, 4 rollup tables)
  lua/token_bucket.lua
```

## Running it locally

```bash
docker compose up -d postgres redis
mvn spring-boot:run
```

Or fully containerized: `docker compose up --build`.

## Deploying it publicly

See `DEPLOYMENT.md` for three concrete paths: Railway (fastest to a live demo link),
Fly.io, and the "real production" AWS architecture (with reasoning for each difference
from the single-box demo).

## Load testing

```bash
k6 run -e BASE_URL=http://localhost:8080 loadtest/redirect_load.js
```

Ramps redirect traffic toward the ~5,000 req/s design target and asserts p99 latency stays
under 15ms. Run this against your actual deployment and put the real numbers in this README
— a measured number beats an unverified scale claim.

**API:**

```bash
# Create (Snowflake mode, default)
curl -X POST localhost:8080/api/v1/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path"}'

# Create (content-hash / idempotent mode)
curl -X POST localhost:8080/api/v1/shorten \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/some/long/path","mode":"CONTENT_HASH"}'

# Redirect (also fires an async analytics event)
curl -i localhost:8080/AbC1234?utm_source=newsletter&utm_medium=email

# Query rollups
curl localhost:8080/api/v1/analytics/AbC1234?days=7
```

## Honest caveats

- **This sandbox has no access to Maven Central**, so I wrote this carefully but could not
  actually run `mvn compile`/`mvn test` here to catch every last typo or API-signature
  mismatch (particularly around the Spring Data Redis Streams API, which has some fiddly
  generics). Run `mvn clean verify` locally as your first step — flag anything that doesn't
  compile and I'll fix it immediately.
- `country` in analytics is wired to read a `CloudFront-Viewer-Country` header but there's
  no real GeoIP lookup — that's the honest state of "geo" in this build; swap in a MaxMind
  DB lookup or your CDN's header if you're not on CloudFront.
- Bot detection is UA-string pattern matching (see `UserAgentInspector`) — a real line of
  defense, but bot detection is fundamentally an arms race; production systems layer in
  behavioral signals (request-rate fingerprinting, missing-header heuristics) on top.
- `SnowflakeIdGenerator`'s `workerId` is a static config value here (`application.yml`). In
  a real multi-node deployment, hand it out via a coordination service at startup instead of
  hardcoding it — two instances with the same worker-id can generate colliding IDs.
- Redis Streams (not Kafka) and Postgres rollups (not ClickHouse) — deliberate right-sizing
  for the stated scale target, not a limitation I forgot about. See ARCHITECTURE.md §4 for
  the explicit trade-off table and the upgrade path.

## What I'd add next, in priority order

1. Integration tests with Testcontainers (Postgres + Redis) — the pure-logic unit tests in
   `src/test` cover Base62/content-hash correctness, but the cache/rate-limit/analytics
   integration paths need real Redis/Postgres to test meaningfully.
2. API-key auth/provisioning (currently `X-API-Key` is trusted as-given, no validation).
3. GeoIP lookup for the `country` field.
4. A small Grafana/dashboard-facing read API beyond the raw JSON `AnalyticsController` gives now.
