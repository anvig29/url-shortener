package com.example.shortener.web.dto;

import java.util.List;

public record AnalyticsSummaryResponse(
        String code,
        List<HourlyPoint> hourly,
        List<BreakdownPoint> byReferrer,
        List<BreakdownPoint> byDevice,
        List<UtmBreakdownPoint> byUtm
) {
    public record HourlyPoint(String bucketHour, long totalClicks, long botClicks) {}
    public record BreakdownPoint(String label, long clicks) {}
    public record UtmBreakdownPoint(String source, String medium, String campaign, long clicks) {}
}
