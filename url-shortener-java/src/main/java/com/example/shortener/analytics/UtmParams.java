package com.example.shortener.analytics;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Extracts utm_source/utm_medium/utm_campaign from the *redirect request's* query string
 * (e.g. GET /abc123?utm_source=newsletter). This is deliberately read from the incoming
 * request, not the stored long URL - campaign attribution is about how the visitor arrived
 * at the short link, which the short link's owner controls by how they distribute it, not
 * a property of the destination URL itself.
 */
public record UtmParams(String source, String medium, String campaign) {

    private static final String NONE = "(none)";

    public static UtmParams empty() {
        return new UtmParams(NONE, NONE, NONE);
    }

    public static UtmParams fromQueryString(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return empty();
        }
        try {
            var params = UriComponentsBuilder.newInstance()
                    .query(queryString)
                    .build()
                    .getQueryParams();
            return new UtmParams(
                    firstOrDefault(params.getFirst("utm_source")),
                    firstOrDefault(params.getFirst("utm_medium")),
                    firstOrDefault(params.getFirst("utm_campaign"))
            );
        } catch (Exception e) {
            // Malformed query string on a redirect should never break the redirect itself.
            return empty();
        }
    }

    private static String firstOrDefault(String value) {
        return (value == null || value.isBlank()) ? NONE : value;
    }
}
