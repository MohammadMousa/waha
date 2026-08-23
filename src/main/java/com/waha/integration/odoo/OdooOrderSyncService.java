package com.waha.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.integration.ExternalMapping;
import com.waha.integration.ExternalMappingRepository;
import com.waha.integration.ExternalSystem;
import com.waha.integration.ExternalSystemRepository;
import com.waha.integration.SyncQueueItem;
import com.waha.integration.SyncQueueRepository;
import com.waha.order.OrderPaidEvent;
import com.waha.order.OrderRepository;
import com.waha.order.dto.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class OdooOrderSyncService {

    private static final Logger log = LoggerFactory.getLogger(OdooOrderSyncService.class);
    private static final String SYSTEM_NAME = "ODOO";
    private static final int MAX_ATTEMPTS = 5;

    // Cached partner_id for the deployment-level override only (same for all orders).
    // Per-user partners are cached in external_mappings (DB), not in memory.
    // Reset via resetPartnerCache() when settings change.
    private volatile Long cachedOverridePartnerId;

    private final ExternalSystemRepository systemRepo;
    private final SyncQueueRepository queueRepo;
    private final ExternalMappingRepository mappingRepo;
    private final OrderRepository orderRepository;
    private final OdooClient odooClient;
    private final ObjectMapper objectMapper;

    public OdooOrderSyncService(ExternalSystemRepository systemRepo,
                                 SyncQueueRepository queueRepo,
                                 ExternalMappingRepository mappingRepo,
                                 OrderRepository orderRepository,
                                 OdooClient odooClient,
                                 ObjectMapper objectMapper) {
        this.systemRepo     = systemRepo;
        this.queueRepo      = queueRepo;
        this.mappingRepo    = mappingRepo;
        this.orderRepository = orderRepository;
        this.odooClient     = odooClient;
        this.objectMapper   = objectMapper;
    }

    // Fires after OrderService marks an order PAID.
    // Enqueues the order for async push to Odoo — does NOT block the sale.
    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        Optional<ExternalSystem> sys = systemRepo.findByName(SYSTEM_NAME);
        if (sys.isEmpty() || !sys.get().enabled()) return;

        try {
            OrderResponse order = orderRepository.getOrderDetail(event.orderId(), true);
            String payload = objectMapper.writeValueAsString(order);
            queueRepo.enqueue(sys.get().id(), "ORDER", event.orderId(), "CREATE", payload, order.storeId());
            log.info("Enqueued order {} for Odoo sync", event.orderId());
        } catch (Exception e) {
            log.error("Failed to enqueue order {} for Odoo sync: {}", event.orderId(), e.getMessage());
        }
    }

    // Processes PENDING sync_queue items every 60 seconds. Also callable manually.
    @Scheduled(fixedDelay = 60_000)
    public int processPendingQueue() {
        Optional<ExternalSystem> sysOpt = systemRepo.findByName(SYSTEM_NAME);
        if (sysOpt.isEmpty() || !sysOpt.get().enabled()) return 0;

        ExternalSystem sys = sysOpt.get();
        List<SyncQueueItem> items = queueRepo.findReadyToProcess(10);
        if (items.isEmpty()) return 0;

        log.info("Processing {} sync_queue items for Odoo", items.size());
        int pushed = 0;
        for (SyncQueueItem item : items) {
            try {
                if ("ORDER".equals(item.entityType()) && "CREATE".equals(item.operation())) {
                    pushOrder(sys, item);
                    queueRepo.markDone(item.id());
                    pushed++;
                } else {
                    log.warn("Unknown sync_queue operation: {}/{}", item.entityType(), item.operation());
                    queueRepo.markFailed(item.id(), "Unknown operation");
                }
            } catch (OdooException e) {
                log.warn("Odoo sync failed for queue item {}: {}", item.id(), e.getMessage());
                int nextAttempts = item.attempts() + 1;
                if (nextAttempts >= MAX_ATTEMPTS) {
                    queueRepo.markFailed(item.id(), e.getMessage());
                } else {
                    queueRepo.incrementAttempts(item.id(), e.getMessage());
                }
            } catch (Exception e) {
                log.error("Unexpected error processing queue item {}: {}", item.id(), e.getMessage());
                queueRepo.incrementAttempts(item.id(), e.getMessage());
            }
        }
        return pushed;
    }

    // Called by OdooAdminController whenever settings are saved.
    public void resetPartnerCache() {
        cachedOverridePartnerId = null;
    }

    // Resolution order (per Odoo Customers spec):
    //   1. Deployment-level customer override → one shared Odoo partner for all orders
    //   2. Per-identity mapping → Waha username → Odoo partner (find by email/name, create if missing)
    // All results are cached in external_mappings to avoid repeated Odoo lookups.
    private long resolveCustomerPartnerId(ExternalSystem sys, String orderUsername) {
        // 1. Override wins if configured.
        String override = sys.customerOverride();
        if (override != null && !override.isBlank()) {
            if (cachedOverridePartnerId != null) return cachedOverridePartnerId;
            Optional<ExternalMapping> cached = mappingRepo.findByLocalId(
                sys.id(), "PARTNER_OVERRIDE", override);
            if (cached.isPresent()) {
                cachedOverridePartnerId = Long.parseLong(cached.get().externalId());
                return cachedOverridePartnerId;
            }
            try {
                long partnerId = findOrCreateNamedPartner(sys, override, null);
                mappingRepo.save(sys.id(), "PARTNER_OVERRIDE", override,
                                 String.valueOf(partnerId), null);
                cachedOverridePartnerId = partnerId;
                log.info("Resolved override partner_id={} for '{}'", partnerId, override);
                return cachedOverridePartnerId;
            } catch (Exception e) {
                log.warn("Cannot resolve override partner '{}': {}", override, e.getMessage());
            }
        }

        // 2. Per-identity: map order username to an Odoo partner.
        // Covers USER (real user email) and DEVICE (kiosk account name) identities.
        // GUEST sessions are not yet present in the system — handled when SHOPPING mode is added.
        if (orderUsername != null && !orderUsername.isBlank()) {
            Optional<ExternalMapping> cached = mappingRepo.findByLocalId(
                sys.id(), "USER", orderUsername);
            if (cached.isPresent()) {
                return Long.parseLong(cached.get().externalId());
            }
            try {
                // Email usernames → match by email field in Odoo; others by name.
                String email = orderUsername.contains("@") ? orderUsername : null;
                long partnerId = findOrCreateNamedPartner(sys, orderUsername, email);
                mappingRepo.save(sys.id(), "USER", orderUsername,
                                 String.valueOf(partnerId), null);
                log.info("Resolved partner_id={} for username '{}'", partnerId, orderUsername);
                return partnerId;
            } catch (Exception e) {
                log.warn("Cannot resolve partner for username '{}': {}", orderUsername, e.getMessage());
            }
        }

        // Last resort: API user's own partner (should rarely reach here).
        try {
            long fallback = odooClient.getPartnerIdForLogin(sys.baseUrl(), sys.apiKey(), sys.username());
            log.warn("Fell back to API user partner_id={}", fallback);
            return fallback;
        } catch (Exception e) {
            log.warn("Cannot resolve any partner_id, using id=3: {}", e.getMessage());
            return 3L;
        }
    }

    // Finds an Odoo partner by email (if provided) or name, creating one if not found.
    private long findOrCreateNamedPartner(ExternalSystem sys, String name, String email) {
        // Search by email first (more precise) then by name.
        List<Object> domain = email != null
            ? List.of(List.of("email", "=", email))
            : List.of(List.of("name", "=", name));
        List<Long> ids = odooClient.search(sys.baseUrl(), sys.apiKey(), sys.username(),
            "res.partner", domain);
        if (!ids.isEmpty()) return ids.get(0);

        Map<String, Object> vals = new HashMap<>();
        vals.put("name", name);
        if (email != null) vals.put("email", email);
        vals.put("customer_rank", 1);
        return odooClient.create(sys.baseUrl(), sys.apiKey(), sys.username(), "res.partner", vals);
    }

    // Fetches the first active tax from Odoo's account.tax model and compares its
    // rate to Waha's configured taxRate. Throws OdooException if they differ by
    // more than 0.1 percentage points — we refuse to push rather than silently
    // corrupt billing figures.
    private void validateOdooTaxRate(ExternalSystem sys, double wahaTaxRate) {
        try {
            List<JsonNode> taxes = odooClient.searchRead(
                sys.baseUrl(), sys.apiKey(), sys.username(),
                "account.tax",
                List.of(List.of("active", "=", true), List.of("type_tax_use", "=", "sale")),
                List.of("name", "amount", "amount_type"),
                1, 0
            );
            if (taxes.isEmpty()) return; // No taxes configured in Odoo — nothing to compare.
            JsonNode tax = taxes.get(0);
            if (!"percent".equals(tax.path("amount_type").asText())) return; // Non-percent type — skip.
            double odooRate = tax.path("amount").asDouble(0.0) / 100.0;
            double wahaPercent  = Math.round(wahaTaxRate * 10000.0) / 100.0;
            double odooPercent  = Math.round(odooRate    * 10000.0) / 100.0;
            if (Math.abs(wahaPercent - odooPercent) > 0.1) {
                throw new OdooException(String.format(
                    "Tax rate mismatch: Waha has %.2f%% but Odoo '%s' is %.2f%%. " +
                    "Fix the tax rate in one system before pushing orders.",
                    wahaPercent, tax.path("name").asText("?"), odooPercent));
            }
        } catch (OdooException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Could not validate Odoo tax rate: {}", e.getMessage());
            // Don't block the push if the tax validation call itself fails.
        }
    }

    private void pushOrder(ExternalSystem sys, SyncQueueItem item) throws Exception {
        JsonNode order = objectMapper.readTree(item.payload());
        String orderId = order.path("orderId").asText();

        // Idempotency: check if already pushed by searching client_order_ref
        List<Long> existing = odooClient.search(
            sys.baseUrl(), sys.apiKey(), sys.username(),
            "sale.order",
            List.of(List.of("client_order_ref", "=", orderId))
        );

        if (!existing.isEmpty()) {
            long odooOrderId = existing.get(0);
            mappingRepo.save(sys.id(), "ORDER", orderId, String.valueOf(odooOrderId), item.storeId());
            log.info("Order {} already exists in Odoo as id={}", orderId, odooOrderId);
            return;
        }

        // Validate that Odoo's tax rate matches Waha's before touching any billing data.
        // Silently adjusting prices or clearing taxes would corrupt accounting records.
        double wahaTaxRate = order.path("taxRate").asDouble(0.0);
        validateOdooTaxRate(sys, wahaTaxRate);

        List<Object> orderLines = new ArrayList<>();
        JsonNode items = order.path("items");
        if (items.isArray()) {
            for (JsonNode lineNode : items) {
                long localProductId = lineNode.path("productId").asLong();
                Optional<ExternalMapping> productMap = mappingRepo.findByLocalId(
                    sys.id(), "PRODUCT", String.valueOf(localProductId));

                Map<String, Object> line = new HashMap<>();
                if (productMap.isPresent()) {
                    line.put("product_id", Long.parseLong(productMap.get().externalId()));
                }
                line.put("product_uom_qty", lineNode.path("quantity").asInt());
                line.put("price_unit",      lineNode.path("unitPrice").asDouble());
                line.put("name",            lineNode.path("name").path("en").asText("Product"));
                orderLines.add(List.of(0, 0, line));
            }
        }

        String orderUsername = order.path("username").asText(null);
        Map<String, Object> values = new HashMap<>();
        values.put("client_order_ref", orderId);
        values.put("order_line",       orderLines);
        values.put("partner_id",       resolveCustomerPartnerId(sys, orderUsername));

        long odooId = odooClient.create(sys.baseUrl(), sys.apiKey(), sys.username(), "sale.order", values);
        // Confirm the quotation so it appears as a confirmed Sales Order in Odoo (not just a draft Quotation).
        odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(), "sale.order", "action_confirm", List.of(odooId));
        mappingRepo.save(sys.id(), "ORDER", orderId, String.valueOf(odooId), item.storeId());
        log.info("Pushed order {} to Odoo as id={}", orderId, odooId);
    }
}
