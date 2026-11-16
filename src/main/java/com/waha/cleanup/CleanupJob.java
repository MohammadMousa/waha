package com.waha.cleanup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Runs the data cleanup every night. What is kept, and for how many days, is set in System Configuration.
@Component
public class CleanupJob {

    private static final Logger log = LoggerFactory.getLogger(CleanupJob.class);

    private final CleanupService cleanup;

    public CleanupJob(CleanupService cleanup) {
        this.cleanup = cleanup;
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void run() {
        try {
            cleanup.runAll("SCHEDULED");
        } catch (RuntimeException e) {
            log.warn("Nightly cleanup failed: {}", e.getMessage());
        }
    }
}
