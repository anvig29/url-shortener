package com.example.shortener.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a batch of raw click events into incremental UPSERTs against the pre-aggregated
 * rollup tables. This is what keeps dashboard queries fast at scale: a dashboard asking
 * "clicks per hour for the last 30 days" reads ~720 rows from clicks_hourly instead of
 * scanning potentially billions of rows in click_events.
 *
 * Events are grouped in-memory by rollup key *within the batch* before hitting the DB, so
 * a batch of 500 clicks on the same viral link becomes one UPSERT with count=500, not 500
 * individual UPSERTs.
 */
@Service
public class RollupService {

    private final JdbcTemplate jdbcTemplate;

    public RollupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void applyBatch(List<ClickEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        insertRawEvents(events);
        upsertHourly(events);
        upsertByReferrer(events);
        upsertByDevice(events);
        upsertByUtm(events);
    }

    private void insertRawEvents(List<ClickEvent> events) {
        jdbcTemplate.batchUpdate(
                """
                INSERT INTO click_events
                    (id, code, occurred_at, referrer, referrer_host, device_type, browser, os,
                     country, utm_source, utm_medium, utm_campaign, is_bot, ip_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                events,
                events.size(),
                (ps, e) -> {
                    ps.setLong(1, e.id());
                    ps.setString(2, e.code());
                    ps.setTimestamp(3, Timestamp.from(e.occurredAt()));
                    ps.setString(4, e.referrer());
                    ps.setString(5, e.referrerHost());
                    ps.setString(6, e.deviceType());
                    ps.setString(7, e.browser());
                    ps.setString(8, e.os());
                    ps.setString(9, e.country());
                    ps.setString(10, e.utmSource());
                    ps.setString(11, e.utmMedium());
                    ps.setString(12, e.utmCampaign());
                    ps.setBoolean(13, e.isBot());
                    ps.setString(14, e.ipHash());
                }
        );
    }

    private void upsertHourly(List<ClickEvent> events) {
        record Key(String code, Instant hourBucket) {}
        Map<Key, long[]> counts = new java.util.HashMap<>(); // [total, bot]
        for (ClickEvent e : events) {
            Instant bucket = e.occurredAt().truncatedTo(ChronoUnit.HOURS);
            Key key = new Key(e.code(), bucket);
            long[] c = counts.computeIfAbsent(key, k -> new long[2]);
            c[0]++;
            if (e.isBot()) c[1]++;
        }
        for (var entry : counts.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO clicks_hourly (code, bucket_hour, total_clicks, bot_clicks)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (code, bucket_hour)
                    DO UPDATE SET total_clicks = clicks_hourly.total_clicks + EXCLUDED.total_clicks,
                                  bot_clicks = clicks_hourly.bot_clicks + EXCLUDED.bot_clicks
                    """,
                    entry.getKey().code(), Timestamp.from(entry.getKey().hourBucket()),
                    entry.getValue()[0], entry.getValue()[1]);
        }
    }

    private void upsertByReferrer(List<ClickEvent> events) {
        record Key(String code, java.time.LocalDate day, String referrerHost) {}
        Map<Key, AtomicLong> counts = groupCount(events, e ->
                new Key(e.code(), dayOf(e), e.referrerHost()));
        for (var entry : counts.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO clicks_by_referrer (code, bucket_day, referrer_host, clicks)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (code, bucket_day, referrer_host)
                    DO UPDATE SET clicks = clicks_by_referrer.clicks + EXCLUDED.clicks
                    """,
                    entry.getKey().code(), entry.getKey().day(), entry.getKey().referrerHost(),
                    entry.getValue().get());
        }
    }

    private void upsertByDevice(List<ClickEvent> events) {
        record Key(String code, java.time.LocalDate day, String deviceType) {}
        Map<Key, AtomicLong> counts = groupCount(events, e ->
                new Key(e.code(), dayOf(e), e.deviceType()));
        for (var entry : counts.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO clicks_by_device (code, bucket_day, device_type, clicks)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (code, bucket_day, device_type)
                    DO UPDATE SET clicks = clicks_by_device.clicks + EXCLUDED.clicks
                    """,
                    entry.getKey().code(), entry.getKey().day(), entry.getKey().deviceType(),
                    entry.getValue().get());
        }
    }

    private void upsertByUtm(List<ClickEvent> events) {
        record Key(String code, java.time.LocalDate day, String source, String medium, String campaign) {}
        Map<Key, AtomicLong> counts = groupCount(events, e ->
                new Key(e.code(), dayOf(e), e.utmSource(), e.utmMedium(), e.utmCampaign()));
        for (var entry : counts.entrySet()) {
            jdbcTemplate.update("""
                    INSERT INTO clicks_by_utm (code, bucket_day, utm_source, utm_medium, utm_campaign, clicks)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (code, bucket_day, utm_source, utm_medium, utm_campaign)
                    DO UPDATE SET clicks = clicks_by_utm.clicks + EXCLUDED.clicks
                    """,
                    entry.getKey().code(), entry.getKey().day(), entry.getKey().source(),
                    entry.getKey().medium(), entry.getKey().campaign(), entry.getValue().get());
        }
    }

    private java.time.LocalDate dayOf(ClickEvent e) {
        return e.occurredAt().atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    private <K> Map<K, AtomicLong> groupCount(List<ClickEvent> events, java.util.function.Function<ClickEvent, K> keyFn) {
        Map<K, AtomicLong> map = new java.util.HashMap<>();
        for (ClickEvent e : events) {
            map.computeIfAbsent(keyFn.apply(e), k -> new AtomicLong()).incrementAndGet();
        }
        return map;
    }
}
