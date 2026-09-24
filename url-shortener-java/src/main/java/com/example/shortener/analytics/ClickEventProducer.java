package com.example.shortener.analytics;

import com.example.shortener.config.ShortenerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The entire "analytics cost" paid on the redirect hot path: one XADD (sub-millisecond,
 * in-memory append-only log write) and nothing else. No DB write, no aggregation, no
 * blocking I/O beyond the Redis round trip itself. If this call fails, we log and let the
 * redirect proceed anyway - a dropped analytics event is an acceptable loss; a failed
 * redirect because analytics infra hiccuped is not (see architecture doc's availability
 * priority: redirect > create > analytics).
 */
@Component
public class ClickEventProducer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventProducer.class);

    private final StringRedisTemplate redisTemplate;
    private final String streamKey;

    public ClickEventProducer(StringRedisTemplate redisTemplate, ShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.streamKey = properties.analytics().streamKey();
    }

    public void publish(String code, String referrer, String userAgent, String ipHash,
                         UtmParams utm, UserAgentInspector.Inspection uaInfo, String country) {
        try {
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("code", code);
            fields.put("occurredAt", Instant.now().toString());
            fields.put("referrer", referrer == null ? "" : referrer);
            fields.put("referrerHost", extractHost(referrer));
            fields.put("deviceType", uaInfo.deviceType());
            fields.put("browser", uaInfo.browser());
            fields.put("os", uaInfo.os());
            fields.put("country", country == null ? "" : country);
            fields.put("utmSource", utm.source());
            fields.put("utmMedium", utm.medium());
            fields.put("utmCampaign", utm.campaign());
            fields.put("isBot", String.valueOf(uaInfo.isBot()));
            fields.put("ipHash", ipHash == null ? "" : ipHash);

            MapRecord<String, String, String> record = StreamRecords.newRecord()
                    .ofMap(fields)
                    .withStreamKey(streamKey);

            redisTemplate.opsForStream().add(record);
        } catch (Exception e) {
            log.warn("Failed to publish click event for code={} - dropping analytics event, redirect unaffected", code, e);
        }
    }

    private String extractHost(String referrer) {
        if (referrer == null || referrer.isBlank()) {
            return "(direct)";
        }
        try {
            java.net.URI uri = java.net.URI.create(referrer);
            return uri.getHost() == null ? "(direct)" : uri.getHost();
        } catch (Exception e) {
            return "(unknown)";
        }
    }
}
