package com.example.shortener.analytics;

import org.springframework.stereotype.Component;
import ua_parser.Client;
import ua_parser.Parser;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Device classification + bot detection from the User-Agent header.
 *
 * Bot filtering matters here specifically because click analytics feeds business decisions
 * (campaign spend, A/B test reads) - uncaught crawler/monitoring/link-preview traffic
 * silently inflates click counts and skews referrer/device breakdowns. We tag events as
 * bot rather than dropping them outright, so raw data stays complete but rollups can (and
 * do, see RollupService) report bot vs human clicks separately.
 */
@Component
public class UserAgentInspector {

    private final Parser uaParser = new Parser();

    // Known crawlers, monitoring probes, and chat-app/social link-unfurlers. This list is
    // necessarily incomplete (bot-detection is an arms race) - it's the honest first line of
    // defense; a production system would layer in behavioral signals (request rate, missing
    // header fingerprints) on top rather than relying on UA string matching alone.
    private static final Pattern BOT_PATTERN = Pattern.compile(
            "bot|crawler|spider|slurp|bingpreview|facebookexternalhit|slackbot|" +
            "twitterbot|discordbot|telegrambot|whatsapp|linkedinbot|pingdom|" +
            "uptimerobot|googlebot|ahrefsbot|semrushbot|curl|wget|python-requests|headlesschrome",
            Pattern.CASE_INSENSITIVE
    );

    public record Inspection(String deviceType, String browser, String os, boolean isBot) {}

    public Inspection inspect(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return new Inspection("unknown", "unknown", "unknown", false);
        }
        if (BOT_PATTERN.matcher(userAgent).find()) {
            return new Inspection("bot", "bot", "unknown", true);
        }

        Client client = uaParser.parse(userAgent);
        String deviceFamily = client.device.family == null ? "" : client.device.family.toLowerCase(Locale.ROOT);
        String deviceType = classifyDevice(deviceFamily, userAgent);

        String browser = client.userAgent.family;
        String os = client.os.family;
        return new Inspection(deviceType, browser, os, false);
    }

    private String classifyDevice(String deviceFamily, String userAgent) {
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (deviceFamily.contains("ipad") || ua.contains("tablet")) {
            return "tablet";
        }
        if (deviceFamily.contains("iphone") || ua.contains("mobile") || ua.contains("android")) {
            return "mobile";
        }
        if ("other".equals(deviceFamily) || deviceFamily.isEmpty()) {
            return "desktop"; // ua_parser's default fallback for standard desktop browsers
        }
        return "desktop";
    }
}
