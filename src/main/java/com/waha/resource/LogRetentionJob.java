package com.waha.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class LogRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(LogRetentionJob.class);

    private final ResourceRepository resourceRepository;

    @Value("${waha.logs.retention-days:30}")
    private int retentionDays;

    public LogRetentionJob(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @Scheduled(cron = "0 0 3 * * *")
    public void purgeOldLogs() {
        Instant cutoff = Instant.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = resourceRepository.deleteLogsOlderThan(cutoff);
        if (deleted > 0) {
            log.info("Log retention: deleted {} device log(s) older than {} days", deleted, retentionDays);
        }
    }
}
