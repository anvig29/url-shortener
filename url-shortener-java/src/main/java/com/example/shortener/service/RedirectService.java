package com.example.shortener.service;

import com.example.shortener.analytics.ClickEventProducer;
import com.example.shortener.analytics.UserAgentInspector;
import com.example.shortener.analytics.UtmParams;
import com.example.shortener.cache.UrlCacheService;
import com.example.shortener.domain.ShortUrl;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The redirect read path: cache lookup (bloom-filtered, singleflight-protected - see
 * UrlCacheService) followed by a fire-and-forget analytics publish. The analytics publish
 * happens after resolving the redirect target but the HTTP response is sent without
 * waiting on it - see ClickEventProducer for why that's safe to treat as best-effort.
 */
@Service
public class RedirectService {

    private final UrlCacheService cacheService;
    private final ClickEventProducer eventProducer;
    private final UserAgentInspector uaInspector;

    public RedirectService(UrlCacheService cacheService,
                            ClickEventProducer eventProducer,
                            UserAgentInspector uaInspector) {
        this.cacheService = cacheService;
        this.eventProducer = eventProducer;
        this.uaInspector = uaInspector;
    }

    public Optional<String> resolveAndRecord(String code, HttpServletRequest request) {
        Optional<ShortUrl> shortUrl = cacheService.lookup(code);
        if (shortUrl.isEmpty()) {
            return Optional.empty();
        }

        String userAgent = request.getHeader("User-Agent");
        UserAgentInspector.Inspection uaInfo = uaInspector.inspect(userAgent);
        UtmParams utm = UtmParams.fromQueryString(request.getQueryString());
        String ipHash = hashIp(resolveClientIp(request));
        // Country would normally come from a GeoIP lookup (MaxMind DB, or trust a
        // CDN-provided header like CloudFront-Viewer-Country) - out of scope for this build,
        // wired as null so the pipeline/schema/rollups are ready to receive it.
        String country = request.getHeader("CloudFront-Viewer-Country");

        eventProducer.publish(code, request.getHeader("Referer"), userAgent, ipHash, utm, uaInfo, country);

        return Optional.of(shortUrl.get().getLongUrl());
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /** IPs are never stored raw (schema comment: "hashed, never raw IP") - only a hash, for rough unique-visitor estimation. */
    private String hashIp(String ip) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(ip.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            return null;
        }
    }
}
