package com.example.shortener.analytics;

import com.example.shortener.config.ShortenerProperties;
import com.example.shortener.idgen.SnowflakeIdGenerator;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Drains the click-event Redis Stream via a consumer group, batches records, and writes
 * them through RollupService. Deliberately a separate process-internal worker rather than
 * inline in the redirect handler - see architecture doc section 3.3: this is what lets
 * analytics throughput/latency scale (and fail) independently of the redirect path.
 *
 * Consumer group semantics matter here: multiple consumer threads (or multiple app
 * instances) can read from the same group and Redis guarantees each message goes to
 * exactly one consumer. If a consumer dies mid-batch before XACK, the message stays
 * pending and gets reclaimed (XCLAIM) by the recovery sweep below - so a crash loses no
 * events, at worst it delays and double-processes on rare overlap, which is why rollup
 * upserts are idempotent-safe additive counters rather than a problem if a batch is ever
 * replayed twice (documented trade-off: at-least-once delivery, not exactly-once).
 */
@Component
public class ClickEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClickEventConsumer.class);
    private static final String CONSUMER_NAME_PREFIX = "worker-";

    private final StringRedisTemplate redisTemplate;
    private final RollupService rollupService;
    private final SnowflakeIdGenerator idGenerator;
    private final ShortenerProperties.Analytics config;

    private ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public ClickEventConsumer(StringRedisTemplate redisTemplate,
                               RollupService rollupService,
                               SnowflakeIdGenerator idGenerator,
                               ShortenerProperties properties) {
        this.redisTemplate = redisTemplate;
        this.rollupService = rollupService;
        this.idGenerator = idGenerator;
        this.config = properties.analytics();
    }

    @PostConstruct
    public void start() {
        ensureConsumerGroupExists();
        executor = Executors.newFixedThreadPool(config.consumerThreads());
        for (int i = 0; i < config.consumerThreads(); i++) {
            String consumerName = CONSUMER_NAME_PREFIX + i;
            executor.submit(() -> runLoop(consumerName));
        }
        log.info("Started {} analytics consumer thread(s) on stream '{}'", config.consumerThreads(), config.streamKey());
    }

    @PreDestroy
    public void stop() {
        running.set(false);
        if (executor != null) {
            executor.shutdown();
            try {
                executor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void ensureConsumerGroupExists() {
        try {
            redisTemplate.opsForStream().createGroup(config.streamKey(), ReadOffset.from("0"), config.consumerGroup());
        } catch (RedisSystemException e) {
            // BUSYGROUP: group already exists from a prior run - expected on restart, not an error.
            if (e.getMessage() == null || !e.getMessage().contains("BUSYGROUP")) {
                throw e;
            }
        }
    }

    private void runLoop(String consumerName) {
        Consumer consumer = Consumer.from(config.consumerGroup(), consumerName);
        while (running.get()) {
            try {
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                        consumer,
                        StreamReadOptions.empty()
                                .count(config.batchSize())
                                .block(Duration.ofMillis(config.batchTimeoutMs())),
                        StreamOffset.create(config.streamKey(), ReadOffset.lastConsumed())
                );

                if (records == null || records.isEmpty()) {
                    continue;
                }

                List<ClickEvent> events = new ArrayList<>(records.size());
                List<RecordId> ids = new ArrayList<>(records.size());
                for (MapRecord<String, Object, Object> record : records) {
                    events.add(toClickEvent(record));
                    ids.add(record.getId());
                }

                rollupService.applyBatch(events);

                redisTemplate.opsForStream().acknowledge(config.streamKey(), config.consumerGroup(),
                        ids.toArray(new RecordId[0]));

            } catch (Exception e) {
                log.error("Analytics consumer '{}' batch failed - will retry on next poll", consumerName, e);
                sleepQuietly(1000);
            }
        }
    }

    private ClickEvent toClickEvent(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        return new ClickEvent(
                idGenerator.nextId(),
                str(fields, "code"),
                Instant.parse(str(fields, "occurredAt")),
                emptyToNull(str(fields, "referrer")),
                str(fields, "referrerHost"),
                str(fields, "deviceType"),
                str(fields, "browser"),
                str(fields, "os"),
                emptyToNull(str(fields, "country")),
                str(fields, "utmSource"),
                str(fields, "utmMedium"),
                str(fields, "utmCampaign"),
                Boolean.parseBoolean(str(fields, "isBot")),
                emptyToNull(str(fields, "ipHash"))
        );
    }

    private String str(Map<Object, Object> fields, String key) {
        Object v = fields.get(key);
        return v == null ? "" : v.toString();
    }

    private String emptyToNull(String s) {
        return (s == null || s.isEmpty()) ? null : s;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
