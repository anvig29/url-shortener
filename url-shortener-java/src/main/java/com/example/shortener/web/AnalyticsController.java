package com.example.shortener.web;

import com.example.shortener.web.dto.AnalyticsSummaryResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Reads exclusively from the pre-aggregated rollup tables (clicks_hourly,
 * clicks_by_referrer, clicks_by_device, clicks_by_utm) - never the raw click_events firehose.
 * That's the entire point of maintaining rollups: this endpoint stays fast (indexed lookups
 * on small tables) regardless of how many billions of raw events have accumulated.
 */
@RestController
public class AnalyticsController {

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/api/v1/analytics/{code}")
    public AnalyticsSummaryResponse summary(@PathVariable String code,
                                             @RequestParam(defaultValue = "7") int days) {
        List<AnalyticsSummaryResponse.HourlyPoint> hourly = jdbcTemplate.query(
                """
                SELECT bucket_hour, total_clicks, bot_clicks FROM clicks_hourly
                WHERE code = ? AND bucket_hour >= now() - (? || ' days')::interval
                ORDER BY bucket_hour
                """,
                (rs, i) -> new AnalyticsSummaryResponse.HourlyPoint(
                        rs.getTimestamp("bucket_hour").toInstant().toString(),
                        rs.getLong("total_clicks"),
                        rs.getLong("bot_clicks")),
                code, days
        );

        List<AnalyticsSummaryResponse.BreakdownPoint> byReferrer = jdbcTemplate.query(
                """
                SELECT referrer_host, SUM(clicks) AS clicks FROM clicks_by_referrer
                WHERE code = ? AND bucket_day >= current_date - ?
                GROUP BY referrer_host ORDER BY clicks DESC LIMIT 20
                """,
                (rs, i) -> new AnalyticsSummaryResponse.BreakdownPoint(rs.getString("referrer_host"), rs.getLong("clicks")),
                code, days
        );

        List<AnalyticsSummaryResponse.BreakdownPoint> byDevice = jdbcTemplate.query(
                """
                SELECT device_type, SUM(clicks) AS clicks FROM clicks_by_device
                WHERE code = ? AND bucket_day >= current_date - ?
                GROUP BY device_type ORDER BY clicks DESC
                """,
                (rs, i) -> new AnalyticsSummaryResponse.BreakdownPoint(rs.getString("device_type"), rs.getLong("clicks")),
                code, days
        );

        List<AnalyticsSummaryResponse.UtmBreakdownPoint> byUtm = jdbcTemplate.query(
                """
                SELECT utm_source, utm_medium, utm_campaign, SUM(clicks) AS clicks FROM clicks_by_utm
                WHERE code = ? AND bucket_day >= current_date - ?
                GROUP BY utm_source, utm_medium, utm_campaign ORDER BY clicks DESC LIMIT 20
                """,
                (rs, i) -> new AnalyticsSummaryResponse.UtmBreakdownPoint(
                        rs.getString("utm_source"), rs.getString("utm_medium"),
                        rs.getString("utm_campaign"), rs.getLong("clicks")),
                code, days
        );

        return new AnalyticsSummaryResponse(code, hourly, byReferrer, byDevice, byUtm);
    }
}
