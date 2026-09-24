package com.example.shortener;

import com.example.shortener.idgen.Base62;
import com.example.shortener.idgen.ContentHashCodeGenerator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdGenTest {

    @Test
    void base62RoundTripsArbitraryLongs() {
        long[] samples = { 0L, 1L, 61L, 62L, 123456789L, Long.MAX_VALUE };
        for (long v : samples) {
            String encoded = Base62.encode(v);
            assertEquals(v, Base62.decode(encoded), "round trip failed for " + v);
        }
    }

    @Test
    void base62RejectsNegativeInput() {
        assertThrows(IllegalArgumentException.class, () -> Base62.encode(-1L));
    }

    @Test
    void base62RejectsInvalidCharacters() {
        assertThrows(IllegalArgumentException.class, () -> Base62.decode("abc!"));
    }

    @Test
    void contentHashIsDeterministicForSameUrl() {
        ContentHashCodeGenerator gen = new ContentHashCodeGenerator();
        String url = "https://example.com/some/very/long/path?query=1";
        assertEquals(gen.generateCode(url, 0), gen.generateCode(url, 0));
        assertEquals(gen.fullHashHex(url), gen.fullHashHex(url));
    }

    @Test
    void contentHashDiffersForDifferentUrls() {
        ContentHashCodeGenerator gen = new ContentHashCodeGenerator();
        String codeA = gen.generateCode("https://example.com/a", 0);
        String codeB = gen.generateCode("https://example.com/b", 0);
        assertNotEquals(codeA, codeB);
    }

    @Test
    void contentHashSaltChangesCodeForCollisionRetry() {
        ContentHashCodeGenerator gen = new ContentHashCodeGenerator();
        String url = "https://example.com/some/path";
        String attempt0 = gen.generateCode(url, 0);
        String attempt1 = gen.generateCode(url, 1);
        assertNotEquals(attempt0, attempt1, "salted retry must change the resulting code");
    }
}
