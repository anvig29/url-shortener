package com.example.shortener.filter;

import com.example.shortener.ratelimit.TokenBucketRateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Gates both the create and redirect paths through independent rate-limit buckets.
 * Deliberately a raw servlet filter (runs before Spring MVC dispatch) so a client that's
 * over budget never even reaches controller/DB/cache code.
 */
@Component
public class RateLimitFilter extends HttpFilter {

    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitFilter(TokenBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        TokenBucketRateLimiter.LimitClass limitClass = classify(request);
        if (limitClass == null) {
            chain.doFilter(request, response);
            return;
        }

        String identity = resolveIdentity(request);
        TokenBucketRateLimiter.Decision decision = rateLimiter.checkAndConsume(limitClass, identity);

        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.tokensRemaining()));

        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterMs() / 1000 + 1));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"retryAfterMs\":" + decision.retryAfterMs() + "}");
            return;
        }

        chain.doFilter(request, response);
    }

    private TokenBucketRateLimiter.LimitClass classify(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if ("POST".equals(method) && path.startsWith("/api/v1/shorten")) {
            return TokenBucketRateLimiter.LimitClass.CREATE;
        }
        // Anything else that isn't an API/analytics/actuator path is treated as a redirect
        // lookup: GET /{code}.
        if ("GET".equals(method) && !path.startsWith("/api/") && !path.startsWith("/actuator/")) {
            return TokenBucketRateLimiter.LimitClass.REDIRECT;
        }
        return null;
    }

    private String resolveIdentity(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        // Behind a load balancer, trust X-Forwarded-For's first hop only if you control the
        // LB; otherwise this header is spoofable by the client. Documented assumption for
        // this build - production would validate against a trusted proxy list.
        String forwardedFor = request.getHeader("X-Forwarded-For");
        String ip = (forwardedFor != null && !forwardedFor.isBlank())
                ? forwardedFor.split(",")[0].trim()
                : request.getRemoteAddr();
        return "ip:" + ip;
    }
}
