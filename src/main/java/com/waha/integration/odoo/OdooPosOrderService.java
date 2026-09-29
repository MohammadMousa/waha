package com.waha.integration.odoo;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.integration.ExternalSystem;
import com.waha.integration.SyncQueueItem;
import com.waha.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// Pushes paid Waha orders into Odoo Point of Sale instead of Sales.
// Kiosks are unattended, so Waha runs its own Odoo sessions, named WAHA/..., the way the
// client's previous kiosk system did: one session per business day, rolled over on the
// first order after the day starts. Points of sale used by anyone else are never touched.
// Zero-config: a branch without an explicit link uses the Odoo point of sale named like the
// branch (created when missing), and payments default to Odoo's "Card" method.
@Service
public class OdooPosOrderService {

    private static final Logger log = LoggerFactory.getLogger(OdooPosOrderService.class);

    public static final String TARGET_SALES = "SALES";
    public static final String TARGET_POS   = "POS";
    public static final String POS_ORDER    = "POS_ORDER";

    private static final DateTimeFormatter ODOO_TS = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private static final String SESSION_PREFIX = "WAHA/";

    private final OdooClient odooClient;
    private final OdooPosLinkRepository links;
    private final StoreRepository stores;
    private final LocalTime dayStart;
    private final ZoneId zone;
    private final Map<Long, Long> companyByConfig = new ConcurrentHashMap<>();
    private final java.util.Set<String> enabledMethods = ConcurrentHashMap.newKeySet();

    public OdooPosOrderService(OdooClient odooClient, OdooPosLinkRepository links, StoreRepository stores,
                               @Value("${waha.odoo.pos-day-start:03:00}") String dayStart,
                               @Value("${waha.odoo.pos-time-zone:Asia/Riyadh}") String zone) {
        this.odooClient = odooClient;
        this.links      = links;
        this.stores     = stores;
        this.dayStart   = LocalTime.parse(dayStart);
        this.zone       = ZoneId.of(zone);
    }

    public record OdooOption(long id, String name) {}

    static String reference(String orderId) {
        return "WAHA/" + orderId;
    }

    public Optional<Long> findExisting(ExternalSystem sys, String orderId) {
        List<JsonNode> found = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.order",
            List.of(List.of("pos_reference", "=", reference(orderId))),
            List.of("id", "state"), 1, 0);
        if (found.isEmpty()) return Optional.empty();
        long id = found.get(0).path("id").asLong();
        // An earlier attempt may have created the order but failed before marking it paid.
        if ("draft".equals(found.get(0).path("state").asText())) markPaid(sys, id);
        return Optional.of(id);
    }

    public long create(ExternalSystem sys, SyncQueueItem item, JsonNode order,
                       List<OdooOrderSyncService.ResolvedLine> lines, long partnerId, Long taxId) {
        long storeId = item.storeId() != null ? item.storeId() : order.path("storeId").asLong();
        long configId = links.findOdooId(sys.id(), OdooPosLinkRepository.BRANCH, String.valueOf(storeId))
            .orElseGet(() -> pointOfSaleForBranch(sys, storeId));
        String payKey = order.path("paymentMethod").asText("");
        long paymentMethodId = links.findOdooId(sys.id(), OdooPosLinkRepository.PAYMENT_METHOD, payKey)
            .orElseGet(() -> cardPaymentMethod(sys));
        ensurePaymentMethodEnabled(sys, configId, paymentMethodId);

        double rate = order.path("taxRate").asDouble(0.0);
        List<Object> orderLines = new ArrayList<>();
        for (OdooOrderSyncService.ResolvedLine l : lines) {
            if (l.odooProductId() == null) {
                throw new OdooException("Product '" + l.name() + "' is not in Odoo, so the POS order cannot be created");
            }
            Map<String, Object> line = new HashMap<>();
            line.put("product_id",          l.odooProductId());
            line.put("name",                l.name());
            line.put("qty",                 l.quantity());
            line.put("price_unit",          l.unitPrice());
            line.put("price_subtotal",      round2(l.lineTotal()));
            line.put("price_subtotal_incl", java.math.BigDecimal.valueOf(l.lineTotal())
                .multiply(java.math.BigDecimal.ONE.add(java.math.BigDecimal.valueOf(rate)))
                .setScale(2, java.math.RoundingMode.HALF_UP).doubleValue());
            if (taxId != null) line.put("tax_ids", List.of(List.of(6, 0, List.of(taxId))));
            orderLines.add(List.of(0, 0, line));
        }

        long sessionId = openSession(sys, configId, storeId);
        double total = order.path("total").asDouble();
        String ref = reference(order.path("orderId").asText());

        Map<String, Object> payment = new HashMap<>();
        payment.put("amount",            total);
        payment.put("payment_method_id", paymentMethodId);
        payment.put("payment_date",      ODOO_TS.format(Instant.now()));

        Map<String, Object> values = new HashMap<>();
        values.put("name",          ref);
        values.put("pos_reference", ref);
        values.put("session_id",    sessionId);
        values.put("company_id",    companyId(sys, configId));
        values.put("partner_id",    partnerId);
        values.put("lines",         orderLines);
        values.put("payment_ids",   List.of(List.of(0, 0, payment)));
        values.put("amount_tax",    order.path("tax").asDouble());
        values.put("amount_total",  total);
        values.put("amount_paid",   total);
        values.put("amount_return", 0.0);

        long odooId = odooClient.create(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.order", values);
        markPaid(sys, odooId);
        return odooId;
    }

    // No explicit link: the Odoo point of sale named like the branch (English name, else Arabic),
    // created with Waha's kiosk settings when none exists.
    private synchronized long pointOfSaleForBranch(ExternalSystem sys, long storeId) {
        String name = branchName(storeId);
        List<Long> matches = new ArrayList<>();
        for (JsonNode c : odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.config",
                List.of(List.of("name", "ilike", name)), List.of("id", "name"), 50, 0)) {
            if (c.path("name").asText().strip().equalsIgnoreCase(name)) matches.add(c.path("id").asLong());
        }
        for (Long id : matches) {
            if (!usedOutsideWaha(sys, id)) return id;
        }
        // A same-named point of sale that others use is returned so the order fails with a clear reason.
        if (!matches.isEmpty()) return matches.get(0);
        return createPointOfSale(sys, name);
    }

    private String branchName(long storeId) {
        StoreRepository.StoreAdminDetail store = stores.findByIdAdmin(storeId)
            .orElseThrow(() -> new OdooException("Branch " + storeId + " does not exist"));
        JsonNode names = store.displayName();
        for (String lang : List.of("en", "ar")) {
            String value = names == null ? "" : names.path(lang).asText("").strip();
            if (!value.isEmpty()) return value;
        }
        return store.name();
    }

    private long createPointOfSale(ExternalSystem sys, String name) {
        Map<String, Object> values = new HashMap<>();
        values.put("name",                  name);
        values.put("cash_control",          false);
        values.put("module_pos_restaurant", false);
        values.put("payment_method_ids",    List.of(List.of(6, 0, List.of(cardPaymentMethod(sys)))));
        copyKioskSettings(sys, values);
        long id = odooClient.create(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.config", values);
        log.info("Created Odoo point of sale '{}' id={}", name, id);
        return id;
    }

    // New points of sale post to the same journals and warehouse as the one Waha used last.
    // With none yet, Odoo's own defaults apply.
    private void copyKioskSettings(ExternalSystem sys, Map<String, Object> values) {
        List<JsonNode> latest = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.session",
            List.of(List.of("name", "like", SESSION_PREFIX)), List.of("config_id"), 1, 0);
        if (latest.isEmpty()) return;
        long templateId = many2oneId(latest.get(0).path("config_id"));
        List<JsonNode> template = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.config",
            List.of(List.of("id", "=", templateId)),
            List.of("journal_id", "invoice_journal_id", "picking_type_id", "crm_team_id"), 1, 0);
        if (template.isEmpty()) return;
        for (String field : List.of("journal_id", "invoice_journal_id", "picking_type_id", "crm_team_id")) {
            long id = many2oneId(template.get(0).path(field));
            if (id > 0) values.put(field, id);
        }
    }

    private volatile Long cardMethodId;

    // Called when Odoo settings are saved: remembered Odoo ids may belong to another database.
    public void resetCaches() {
        cardMethodId = null;
        companyByConfig.clear();
        enabledMethods.clear();
    }

    private long cardPaymentMethod(ExternalSystem sys) {
        Long cached = cardMethodId;
        if (cached != null) return cached;
        for (JsonNode m : odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.payment.method",
                List.of(List.of("name", "ilike", "Card")), List.of("id", "name"), 50, 0)) {
            if ("card".equalsIgnoreCase(m.path("name").asText().strip())) {
                cardMethodId = m.path("id").asLong();
                return cardMethodId;
            }
        }
        throw new OdooException("Odoo has no POS payment method named 'Card'");
    }

    // Odoo only accepts payments with methods switched on for that point of sale.
    private void ensurePaymentMethodEnabled(ExternalSystem sys, long configId, long methodId) {
        String key = configId + ":" + methodId;
        if (enabledMethods.contains(key)) return;
        // Asked as a search: the XML-RPC reader keeps only the first id of list fields.
        boolean enabled = !odooClient.search(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.config",
            List.of(List.of("id", "=", configId), List.of("payment_method_ids", "in", List.of(methodId)))).isEmpty();
        if (!enabled) {
            odooClient.write(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.config", List.of(configId),
                Map.of("payment_method_ids", List.of(List.of(4, methodId))));
            log.info("Enabled Odoo payment method {} on point of sale {}", methodId, configId);
        }
        enabledMethods.add(key);
    }

    // Returns today's Waha session for this point of sale, closing yesterday's first if needed.
    private long openSession(ExternalSystem sys, long configId, long storeId) {
        if (usedOutsideWaha(sys, configId)) {
            throw new OdooException("Odoo point of sale " + configId + " is used outside Waha; Waha will not send orders to it");
        }
        List<JsonNode> sessions = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.session",
            List.of(List.of("config_id", "=", configId), List.of("state", "!=", "closed")),
            List.of("id", "state", "start_at"), 10, 0);
        for (JsonNode s : sessions) {
            long id = s.path("id").asLong();
            switch (s.path("state").asText()) {
                case "opened" -> {
                    if (!startedBeforeBusinessDay(s.path("start_at").asText())) return id;
                    closeSession(sys, id);
                    log.info("Closed Odoo POS session id={} at the start of a new business day", id);
                }
                case "opening_control" -> {
                    finishOpening(sys, id);
                    return id;
                }
                case "closing_control" -> odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
                    "pos.session", "action_pos_session_close", List.of(id));
                default -> { }
            }
        }

        Map<String, Object> values = new HashMap<>();
        values.put("config_id", configId);
        values.put("user_id",   odooClient.uid(sys.baseUrl(), sys.apiKey(), sys.username()));
        long id = odooClient.create(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.session", values);
        // Odoo names sessions from its own sequence; the WAHA/ name marks the session as Waha's.
        odooClient.write(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.session", List.of(id),
            Map.of("name", SESSION_PREFIX + storeId + "/" + ODOO_TS.format(Instant.now())));
        finishOpening(sys, id);
        log.info("Opened Odoo POS session id={} for point of sale {}", id, configId);
        return id;
    }

    // Odoo 19 leaves a new session at the opening step until the opening cash is confirmed,
    // which the POS screen normally does. A kiosk has no cash drawer, so it confirms 0.
    private void finishOpening(ExternalSystem sys, long sessionId) {
        odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.session", "action_pos_session_open", List.of(sessionId));
        if ("opening_control".equals(sessionState(sys, sessionId))) {
            odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
                "pos.session", "set_opening_control", List.of(sessionId), List.of(0, ""));
        }
        String state = sessionState(sys, sessionId);
        if (!"opened".equals(state)) {
            throw new OdooException("Odoo POS session " + sessionId + " did not open (state: " + state + ")");
        }
    }

    private String sessionState(ExternalSystem sys, long sessionId) {
        List<JsonNode> rows = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.session", List.of(List.of("id", "=", sessionId)), List.of("state"), 1, 0);
        return rows.isEmpty() ? "missing" : rows.get(0).path("state").asText();
    }

    // True when any session on this point of sale was not opened by Waha (cashiers, other kiosk systems).
    private boolean usedOutsideWaha(ExternalSystem sys, long configId) {
        return !odooClient.search(sys.baseUrl(), sys.apiKey(), sys.username(), "pos.session",
            List.of(List.of("config_id", "=", configId), List.of("name", "not like", SESSION_PREFIX))).isEmpty();
    }

    private boolean startedBeforeBusinessDay(String startAtUtc) {
        if (startAtUtc == null || startAtUtc.isBlank() || "false".equals(startAtUtc)) return false;
        ZonedDateTime started = LocalDateTime.parse(startAtUtc, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            .atZone(ZoneOffset.UTC);
        ZonedDateTime now = ZonedDateTime.now(zone);
        ZonedDateTime dayBegan = now.toLocalDate().atTime(dayStart).atZone(zone);
        if (now.isBefore(dayBegan)) dayBegan = dayBegan.minusDays(1);
        return started.isBefore(dayBegan);
    }

    private void closeSession(ExternalSystem sys, long sessionId) {
        odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.session", "action_pos_session_closing_control", List.of(sessionId));
        List<JsonNode> after = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.session", List.of(List.of("id", "=", sessionId)), List.of("state"), 1, 0);
        // With cash control on, the first call only moves the session to closing_control.
        if (!after.isEmpty() && "closing_control".equals(after.get(0).path("state").asText())) {
            odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
                "pos.session", "action_pos_session_close", List.of(sessionId));
        }
    }

    private void markPaid(ExternalSystem sys, long posOrderId) {
        odooClient.callMethod(sys.baseUrl(), sys.apiKey(), sys.username(),
            "pos.order", "action_pos_order_paid", List.of(posOrderId));
    }

    private long companyId(ExternalSystem sys, long configId) {
        return companyByConfig.computeIfAbsent(configId, id -> {
            List<JsonNode> rows = odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
                "pos.config", List.of(List.of("id", "=", id)), List.of("company_id"), 1, 0);
            if (rows.isEmpty()) throw new OdooException("Odoo point of sale " + id + " does not exist");
            return many2oneId(rows.get(0).path("company_id"));
        });
    }

    // Only points of sale nobody else uses, so Waha never mixes into a cashier's or another system's sessions.
    public List<OdooOption> listPointsOfSale(ExternalSystem sys) {
        return options(sys, "pos.config").stream().filter(o -> !usedOutsideWaha(sys, o.id())).toList();
    }

    public boolean isUsableByWaha(ExternalSystem sys, long configId) {
        return !usedOutsideWaha(sys, configId);
    }

    public List<OdooOption> listPaymentMethods(ExternalSystem sys) {
        return options(sys, "pos.payment.method");
    }

    private List<OdooOption> options(ExternalSystem sys, String model) {
        List<OdooOption> out = new ArrayList<>();
        for (JsonNode r : odooClient.searchRead(sys.baseUrl(), sys.apiKey(), sys.username(),
                model, List.of(), List.of("id", "name"), 500, 0)) {
            out.add(new OdooOption(r.path("id").asLong(), r.path("name").asText()));
        }
        return out;
    }

    private static long many2oneId(JsonNode node) {
        if (node.isArray() && node.size() > 0) return node.get(0).asLong();
        return node.asLong();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
