package com.example.shortener.web;

import com.example.shortener.service.RedirectService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
public class RedirectController {

    private final RedirectService redirectService;

    public RedirectController(RedirectService redirectService) {
        this.redirectService = redirectService;
    }

    /**
     * 302 (temporary redirect), not 301 - see architecture doc: a 301 gets cached by
     * browsers/CDNs, so the click-tracking request never fires again after the first visit.
     * That's the whole analytics product broken to save one redirect hop.
     */
    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        Optional<String> longUrl = redirectService.resolveAndRecord(code, request);
        if (longUrl.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, longUrl.get())
                .build();
    }
}
