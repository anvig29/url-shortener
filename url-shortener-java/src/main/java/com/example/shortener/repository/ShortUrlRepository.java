package com.example.shortener.repository;

import com.example.shortener.domain.ShortUrl;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByCode(String code);
    Optional<ShortUrl> findByContentHash(String contentHash);
    boolean existsByCode(String code);
}
