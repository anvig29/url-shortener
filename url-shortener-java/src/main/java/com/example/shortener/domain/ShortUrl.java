package com.example.shortener.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "short_urls")
public class ShortUrl {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 16)
    private String code;

    @Column(name = "long_url", nullable = false, columnDefinition = "TEXT")
    private String longUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "hash_mode", nullable = false, length = 16)
    private HashMode hashMode;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "api_key", length = 128)
    private String apiKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    protected ShortUrl() {}

    public ShortUrl(Long id, String code, String longUrl, HashMode hashMode,
                     String contentHash, String apiKey, Instant expiresAt) {
        this.id = id;
        this.code = code;
        this.longUrl = longUrl;
        this.hashMode = hashMode;
        this.contentHash = contentHash;
        this.apiKey = apiKey;
        this.createdAt = Instant.now();
        this.expiresAt = expiresAt;
        this.active = true;
    }

    public enum HashMode { SNOWFLAKE, CONTENT_HASH }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getLongUrl() { return longUrl; }
    public HashMode getHashMode() { return hashMode; }
    public String getContentHash() { return contentHash; }
    public String getApiKey() { return apiKey; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isActive() { return active; }
}
