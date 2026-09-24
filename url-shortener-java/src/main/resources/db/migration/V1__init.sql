-- ============================================================
-- Core mapping table: source of truth for code -> long URL
-- ============================================================
CREATE TABLE short_urls (
    id              BIGINT PRIMARY KEY,           -- snowflake id (also drives the code for snowflake mode)
    code            VARCHAR(16) NOT NULL,
    long_url        TEXT NOT NULL,
    hash_mode       VARCHAR(16) NOT NULL,         -- 'SNOWFLAKE' | 'CONTENT_HASH'
    content_hash    VARCHAR(64),                  -- populated only for CONTENT_HASH mode (sha256 hex)
    api_key         VARCHAR(128),                 -- owner, nullable for anonymous
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE UNIQUE INDEX ux_short_urls_code ON short_urls (code);
-- Lets content-hash mode do "does this URL already have a code" in O(1) instead of scanning long_url.
CREATE UNIQUE INDEX ux_short_urls_content_hash ON short_urls (content_hash) WHERE content_hash IS NOT NULL;
CREATE INDEX ix_short_urls_api_key ON short_urls (api_key);

-- ============================================================
-- Raw click events — partitioned by day. Partitioning keeps
-- indexes small and lets us drop old partitions cheaply instead
-- of paying for DELETE at row-scale.
-- ============================================================
CREATE TABLE click_events (
    id              BIGINT NOT NULL,
    code            VARCHAR(16) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    referrer        TEXT,
    referrer_host   VARCHAR(255),
    device_type     VARCHAR(16),                  -- 'desktop' | 'mobile' | 'tablet' | 'bot' | 'unknown'
    browser         VARCHAR(64),
    os              VARCHAR(64),
    country         VARCHAR(2),
    utm_source      VARCHAR(128),
    utm_medium      VARCHAR(128),
    utm_campaign    VARCHAR(128),
    is_bot          BOOLEAN NOT NULL DEFAULT FALSE,
    ip_hash         VARCHAR(64),                  -- hashed, never raw IP
    PRIMARY KEY (id, occurred_at)
) PARTITION BY RANGE (occurred_at);

-- Bootstrap partitions; a scheduled job (see PartitionMaintenanceJob) creates future ones.
CREATE TABLE click_events_default PARTITION OF click_events DEFAULT;

CREATE INDEX ix_click_events_code_time ON click_events (code, occurred_at DESC);

-- ============================================================
-- Pre-aggregated rollups — what dashboards actually query.
-- Upserted incrementally by the analytics consumer so reads
-- never have to scan click_events.
-- ============================================================
CREATE TABLE clicks_hourly (
    code            VARCHAR(16) NOT NULL,
    bucket_hour     TIMESTAMPTZ NOT NULL,
    total_clicks    BIGINT NOT NULL DEFAULT 0,
    bot_clicks      BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (code, bucket_hour)
);

CREATE TABLE clicks_by_referrer (
    code            VARCHAR(16) NOT NULL,
    bucket_day      DATE NOT NULL,
    referrer_host   VARCHAR(255) NOT NULL,
    clicks          BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (code, bucket_day, referrer_host)
);

CREATE TABLE clicks_by_device (
    code            VARCHAR(16) NOT NULL,
    bucket_day      DATE NOT NULL,
    device_type     VARCHAR(16) NOT NULL,
    clicks          BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (code, bucket_day, device_type)
);

CREATE TABLE clicks_by_utm (
    code            VARCHAR(16) NOT NULL,
    bucket_day      DATE NOT NULL,
    utm_source      VARCHAR(128) NOT NULL DEFAULT '(none)',
    utm_medium      VARCHAR(128) NOT NULL DEFAULT '(none)',
    utm_campaign    VARCHAR(128) NOT NULL DEFAULT '(none)',
    clicks          BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (code, bucket_day, utm_source, utm_medium, utm_campaign)
);

CREATE INDEX ix_clicks_hourly_code ON clicks_hourly (code, bucket_hour DESC);
CREATE INDEX ix_clicks_by_referrer_code ON clicks_by_referrer (code, bucket_day DESC);
CREATE INDEX ix_clicks_by_device_code ON clicks_by_device (code, bucket_day DESC);
CREATE INDEX ix_clicks_by_utm_code ON clicks_by_utm (code, bucket_day DESC);
