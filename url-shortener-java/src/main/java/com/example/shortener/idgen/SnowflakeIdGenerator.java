package com.example.shortener.idgen;

import com.example.shortener.config.ShortenerProperties;
import org.springframework.stereotype.Component;

/**
 * Twitter-Snowflake-style ID generator: 41 bits timestamp (ms since custom epoch) +
 * 10 bits worker id + 12 bits per-ms sequence = 63 bits, fits in a signed long.
 *
 * Why this over "random string, retry on collision": uniqueness is *structural*
 * (time + worker + sequence can never repeat), so creating a short URL never needs a
 * uniqueness check or retry loop against the database. That removes the single biggest
 * source of write-path latency variance in naive shorteners once the keyspace fills up.
 *
 * Trade-off, stated plainly: IDs are roughly time-ordered, so codes derived from them are
 * roughly sortable/guessable-by-recency. Acceptable here (link content isn't secret); if it
 * mattered, you'd bit-shuffle the output before base62-encoding it.
 *
 * workerId MUST be unique per running instance. In this build it's a static config value
 * (see application.yml); in a real multi-node deployment you'd hand it out via a
 * coordination service (Zookeeper znode, or a Postgres advisory-lock-based allocator) at
 * startup instead of hardcoding it.
 */
@Component
public class SnowflakeIdGenerator {

    // Custom epoch: 2024-01-01T00:00:00Z, in ms. Keeps generated values smaller/denser
    // than using the Unix epoch, which matters for base62 code length.
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 10L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = (1L << WORKER_ID_BITS) - 1;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;

    private final long workerId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdGenerator(ShortenerProperties properties) {
        this.workerId = properties.snowflake().workerId();
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                    "shortener.snowflake.worker-id must be between 0 and " + MAX_WORKER_ID);
        }
    }

    public synchronized long nextId() {
        long timestamp = currentTimeMillis();

        if (timestamp < lastTimestamp) {
            // Clock moved backwards (NTP correction, VM pause). Refusing to generate an ID
            // that could collide with one already issued is safer than silently reusing time.
            throw new IllegalStateException(
                    "Clock moved backwards by " + (lastTimestamp - timestamp) + "ms; refusing to generate ID");
        }

        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence exhausted (4096 IDs in this ms on this worker) - spin to next ms.
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitNextMillis(long lastTs) {
        long ts = currentTimeMillis();
        while (ts <= lastTs) {
            ts = currentTimeMillis();
        }
        return ts;
    }

    protected long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
