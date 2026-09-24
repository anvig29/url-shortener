package com.example.shortener.analytics;

import java.time.Instant;

public record ClickEvent(
        long id,
        String code,
        Instant occurredAt,
        String referrer,
        String referrerHost,
        String deviceType,
        String browser,
        String os,
        String country,
        String utmSource,
        String utmMedium,
        String utmCampaign,
        boolean isBot,
        String ipHash
) {}
