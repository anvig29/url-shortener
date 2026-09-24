package com.example.shortener.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ShortenRequest(
        @NotBlank @Pattern(regexp = "^https?://.+", message = "url must start with http:// or https://")
        String url,

        // "SNOWFLAKE" (default, unique code every call) or "CONTENT_HASH" (idempotent - same
        // URL returns the same code on repeat calls).
        String mode
) {
    public String modeOrDefault() {
        return mode == null || mode.isBlank() ? "SNOWFLAKE" : mode.toUpperCase();
    }
}
