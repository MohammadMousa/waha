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

    @Scheduled(cron = "0 0 6 * * *")
    public void runDailySync() {
        systemRepo.findByName("ODOO").ifPresent(sys -> runSync(sys, "SCHEDULED"));
    }

    public SyncResult runSync(ExternalSystem sys, String triggeredBy) {
        if (!sys.enabled()) {
            log.info("Odoo sync skipped — integration disabled");
            return new SyncResult(0, 0, false, "Integration is disabled");
        }
        log.info("Odoo sync starting (system={}, trigger={})", sys.id(), triggeredBy);
        long logId = systemRepo.insertCatalogPullLog(sys.id(), triggeredBy);
        int cats = 0, prods = 0;
        try {
            cats  = catalogService.pullCategories();
            prods = catalogService.pullProducts();
            systemRepo.completeCatalogPullLog(logId, cats, prods);
            log.info("Odoo sync completed — categories={} products={}", cats, prods);
            return new SyncResult(cats, prods, true, null);
        } catch (Exception e) {
            systemRepo.failCatalogPullLog(logId, e.getMessage());
            log.error("Odoo sync failed", e);
            return new SyncResult(cats, prods, false, e.getMessage());
        }
    }

    public record SyncResult(int categoriesPulled, int productsPulled, boolean success, String errorMessage) {}
}
