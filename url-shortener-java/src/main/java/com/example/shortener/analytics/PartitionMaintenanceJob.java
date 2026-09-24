package com.example.shortener.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * click_events is range-partitioned by day (see V1__init.sql) so old partitions can be
 * dropped cheaply instead of paying for a row-by-row DELETE at billions-of-rows scale, and
 * so indexes stay small per-partition. This job keeps a rolling week of partitions
 * pre-created; anything that misses (e.g. this job is down for a few days) still lands
 * safely in click_events_default rather than failing inserts, just without partition
 * pruning benefits until the next run catches up.
 */
@Component
public class PartitionMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenanceJob.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int DAYS_AHEAD = 7;

    private final JdbcTemplate jdbcTemplate;

    public PartitionMaintenanceJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "0 0 1 * * *") // daily at 01:00
    public void createUpcomingPartitions() {
        LocalDate today = LocalDate.now();
        for (int i = 0; i < DAYS_AHEAD; i++) {
            LocalDate day = today.plusDays(i);
            createPartitionForDay(day);
        }
    }

    private void createPartitionForDay(LocalDate day) {
        String partitionName = "click_events_" + day.format(DATE_FMT).replace("-", "_");
        LocalDate next = day.plusDays(1);
        try {
            jdbcTemplate.execute(String.format(
                    "CREATE TABLE IF NOT EXISTS %s PARTITION OF click_events FOR VALUES FROM ('%s') TO ('%s')",
                    partitionName, day, next));
        } catch (Exception e) {
            // Most common cause: the range overlaps the default partition's existing data,
            // which requires detaching/reattaching - logged, not fatal, inserts still work
            // via click_events_default.
            log.warn("Could not create partition {} - inserts for that day will land in the default partition", partitionName, e);
        }
    }
}
