package com.example.shortener.web.dto;

public record ShortenResponse(String code, String shortUrl, String longUrl, String hashMode) {}
