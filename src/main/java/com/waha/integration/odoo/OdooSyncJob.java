package com.waha.integration.odoo;

import com.waha.integration.ExternalSystem;
import com.waha.integration.ExternalSystemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OdooSyncJob {

    private static final Logger log = LoggerFactory.getLogger(OdooSyncJob.class);

    private final ExternalSystemRepository systemRepo;
    private final OdooCatalogService catalogService;

    public OdooSyncJob(ExternalSystemRepository systemRepo, OdooCatalogService catalogService) {
        this.systemRepo     = systemRepo;
        this.catalogService = catalogService;
    }

    private static final int MAX_RETRIES = 3;

    // Retries left after a failed nightly pull (kept in memory: a restart drops them).
    private volatile int retriesLeft = 0;

    @Scheduled(cron = "0 0 3 * * *")
    public void runDailySync() {
        retriesLeft = 0;
        systemRepo.findByName("ODOO").ifPresent(sys -> {
            SyncResult r = runSync(sys, "SCHEDULED");
            if (!r.success() && sys.enabled()) retriesLeft = MAX_RETRIES;
        });
    }

    // A failed nightly pull (for example a network or DNS blip) is tried again every 15 minutes, up to 3 times.
    @Scheduled(fixedDelay = 15 * 60_000L, initialDelay = 15 * 60_000L)
    public void retryFailedSync() {
        if (retriesLeft <= 0) return;
        retriesLeft--;
        systemRepo.findByName("ODOO").ifPresent(sys -> {
            SyncResult r = runSync(sys, "SCHEDULED");
            if (r.success()) retriesLeft = 0;
        });
    }

    public SyncResult runSync(ExternalSystem sys, String triggeredBy) {
        if (!sys.enabled()) {
            log.info("Odoo sync skipped — integration disabled");
            return new SyncResult(0, 0, 0, false, "Integration is disabled");
        }
        log.info("Odoo sync starting (system={}, trigger={})", sys.id(), triggeredBy);
        long logId = systemRepo.insertCatalogPullLog(sys.id(), triggeredBy);
        int cats = 0, prods = 0, skipped = 0;
        try {
            cats  = catalogService.pullCategories();
            OdooCatalogService.ProductPullResult products = catalogService.pullProducts();
            prods = products.processed();
            skipped = products.skipped();
            systemRepo.completeCatalogPullLog(logId, cats, prods, skipped, products.skipReasons());
            log.info("Odoo sync completed — categories={} products={}", cats, prods);
            return new SyncResult(cats, prods, skipped, true, null);
        } catch (Exception e) {
            systemRepo.failCatalogPullLog(logId, e.getMessage());
            log.error("Odoo sync failed", e);
            return new SyncResult(cats, prods, skipped, false, e.getMessage());
        }
    }

    public record SyncResult(int categoriesPulled, int productsPulled, int productsSkipped, boolean success, String errorMessage) {}
}
