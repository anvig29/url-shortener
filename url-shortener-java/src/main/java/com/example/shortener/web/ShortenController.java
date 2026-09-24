package com.example.shortener.web;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.domain.ShortUrl;
import com.example.shortener.service.UrlShortenerService;
import com.example.shortener.web.dto.ShortenRequest;
import com.example.shortener.web.dto.ShortenResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shorten")
public class ShortenController {

    private final UrlShortenerService shortenerService;
    private final ShortenerProperties properties;

    public ShortenController(UrlShortenerService shortenerService, ShortenerProperties properties) {
        this.shortenerService = shortenerService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<ShortenResponse> shorten(@Valid @RequestBody ShortenRequest request,
                                                     HttpServletRequest httpRequest) {
        String apiKey = httpRequest.getHeader("X-API-Key");

        ShortUrl shortUrl = switch (request.modeOrDefault()) {
            case "CONTENT_HASH" -> shortenerService.createContentHash(request.url(), apiKey);
            case "SNOWFLAKE" -> shortenerService.createSnowflake(request.url(), apiKey);
            default -> throw new IllegalArgumentException(
                    "mode must be SNOWFLAKE or CONTENT_HASH, got: " + request.mode());
        };

        ShortenResponse response = new ShortenResponse(
                shortUrl.getCode(),
                properties.baseUrl() + "/" + shortUrl.getCode(),
                shortUrl.getLongUrl(),
                shortUrl.getHashMode().name()
        );
        return ResponseEntity.ok(response);
    }
}
