package com.waha.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

// Nightly cleanup of the removals list. The keep period is the longest time a device may stay offline and still
// catch up from the list; an older device is told to pull everything again.
@Component
public class SyncRemovalsJob {

    private static final Logger log = LoggerFactory.getLogger(SyncRemovalsJob.class);

    private final SyncRemovalRepository removals;

    @Value("${waha.sync.removals-retention-days:180}")
    private int retentionDays;

    public SyncRemovalsJob(SyncRemovalRepository removals) {
        this.removals = removals;
    }

    @Scheduled(cron = "0 15 3 * * *")
    public void purgeOld() {
        int deleted = removals.purgeOlderThan(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
        if (deleted > 0) {
            log.info("Sync removals: deleted {} row(s) older than {} days", deleted, retentionDays);
        }
    }
}
