package com.example.shortener.idgen;

/**
 * Standard base62 codec over [0-9a-zA-Z]. Used to turn 63-bit snowflake IDs (or hash
 * prefixes) into short, URL-safe, case-sensitive codes.
 *
 * A 63-bit long needs at most 11 base62 characters; in practice snowflake IDs generated
 * within a few decades of the chosen epoch encode to 7-8 characters, matching the length
 * users expect from a "short" URL.
 */
public final class Base62 {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int BASE = ALPHABET.length;
    private static final int[] REVERSE = new int[128];

    static {
        for (int i = 0; i < ALPHABET.length; i++) {
            REVERSE[ALPHABET[i]] = i;
        }
    }

    private Base62() {}

    public static String encode(long value) {
        if (value == 0) {
            return String.valueOf(ALPHABET[0]);
        }
        // Long.MIN_VALUE has no positive counterpart; we only ever feed non-negative
        // snowflake IDs / masked hash prefixes in, so guard defensively instead of
        // silently misencoding.
        if (value < 0) {
            throw new IllegalArgumentException("Base62.encode requires a non-negative value: " + value);
        }
        StringBuilder sb = new StringBuilder();
        long v = value;
        while (v > 0) {
            int rem = (int) (v % BASE);
            sb.append(ALPHABET[rem]);
            v /= BASE;
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            result = result * BASE + digitValue(code.charAt(i));
        }
        return result;
    }

    private static int digitValue(char c) {
        if (c < REVERSE.length) {
            int idx = REVERSE[c];
            if (ALPHABET[idx] == c) {
                return idx;
            }
        }
        throw new IllegalArgumentException("Invalid base62 character: " + c);
    }
}
