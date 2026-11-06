package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.waha.device.DeviceRepository;
import com.waha.order.OrderPaidEvent;
import com.waha.order.OrderRepository;
import com.waha.store.StoreConfig;
import com.waha.store.StoreRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

// Receives an order that a kiosk created and paid on its own (docs/offline.md section 6).
// The whole order is one transaction: it is saved completely or not at all. Sending the same order
// again is safe, and an order first sent unpaid can be sent again once it is paid.
// The server does not reprice: it checks the arithmetic and that the products exist. A price that
// differs from today's price only marks the order (price_differs).
@Service
public class OfflineOrderService {

    public record Result(String result, String orderId, long displayId, String orderReference) {}

    // What to do with an order the server already holds.
    enum Action { NOTHING, MARK_PAID, MARK_CANCELLED, CANCELLED_CONFLICT }

    private static final Set<String> STATUSES = Set.of("CREATED", "PENDING", "PAID", "CANCELLED");
    private static final java.util.regex.Pattern REFERENCE_FORMAT = java.util.regex.Pattern.compile("^[A-Za-z0-9._-]{1,30}$");
    private static final java.util.regex.Pattern UUID_FORMAT =
        java.util.regex.Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StoreRepository storeRepository;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;
    private final OrderRepository orderRepository;

    public OfflineOrderService(JdbcTemplate jdbc, PlatformTransactionManager txManager, StoreRepository storeRepository,
                               ApplicationEventPublisher events, ObjectMapper mapper, OrderRepository orderRepository) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(txManager);
        this.storeRepository = storeRepository;
        this.events = events;
        this.mapper = mapper;
        this.orderRepository = orderRepository;
    }

    // device: the kiosk that is pushing. Throws OfflineOrderException when refused.
    public Result push(OfflineOrderRequest r, DeviceRepository.SyncInfo device) {
        long deviceStoreId = device.storeId();
        long orgId;
        StoreConfig store;
        try {
            orgId = storeRepository.findCompanyId(deviceStoreId);
            store = storeRepository.getStoreConfig(deviceStoreId);
        } catch (RuntimeException e) {
            throw OfflineOrderException.invalid("STORE_MISMATCH", "Store of this device not found");
        }
        validate(r, deviceStoreId, store);

        Outcome out;
        try {
            out = tx.execute(s -> save(r, orgId, store, device));
        } catch (DuplicateKeyException e) {
            // Two pushes of the same order raced: look again once, the second time it finds the order.
            out = tx.execute(s -> save(r, orgId, store, device));
        }
        if (out.becamePaid()) {
            events.publishEvent(new OrderPaidEvent(r.orderId(), deviceStoreId, r.currency()));
        }
        return new Result(out.result(), r.orderId(), out.displayId(), out.reference());
    }

    private record Outcome(String result, boolean becamePaid, long displayId, String reference) {}

    // ── validation (no database writes) ───────────────────────────────────────

    void validate(OfflineOrderRequest r, long deviceStoreId, StoreConfig store) {
        if (r == null || r.orderId() == null || !UUID_FORMAT.matcher(r.orderId()).matches())
            throw OfflineOrderException.invalid("INVALID_REQUEST", "orderId must be a UUID");
        if (r.storeId() == null || r.storeId() != deviceStoreId)
            throw OfflineOrderException.invalid("STORE_MISMATCH", "The order belongs to another store than this device");
        if (r.username() == null || r.username().isBlank())
            throw OfflineOrderException.invalid("INVALID_REQUEST", "username is required");
        if (r.status() == null || !STATUSES.contains(r.status()))
            throw OfflineOrderException.invalid("INVALID_REQUEST", "status must be CREATED, PENDING, PAID or CANCELLED");
        if (r.currency() == null || !r.currency().equals(store.currency()))
            throw OfflineOrderException.invalid("CURRENCY_MISMATCH", "Currency does not match the store");
        if (r.taxRate() == null || r.taxRate().signum() < 0 || r.taxRate().compareTo(BigDecimal.ONE) > 0)
            throw OfflineOrderException.invalid("INVALID_REQUEST", "taxRate must be a fraction between 0 and 1");
        if (r.orderReference() != null && !r.orderReference().isBlank() && !REFERENCE_FORMAT.matcher(r.orderReference().trim()).matches())
            throw OfflineOrderException.invalid("INVALID_REQUEST", "orderReference may have letters, digits, dot, dash and underscore, up to 30 characters");

        if (r.items() == null || r.items().isEmpty())
            throw OfflineOrderException.invalid("INVALID_REQUEST", "An order needs at least one line");
        Set<Integer> lineNos = new HashSet<>();
        for (var i : r.items()) {
            if (i.lineNo() == null || i.lineNo() < 1 || !lineNos.add(i.lineNo()))
                throw OfflineOrderException.invalid("INVALID_REQUEST", "lineNo must be 1 or more and unique inside the order");
            if (i.productId() == null || i.quantity() == null || i.quantity() < 1 || i.unitPrice() == null
                || i.unitPrice().signum() < 0 || i.unitPrice().scale() > 2)
                throw OfflineOrderException.invalid("INVALID_REQUEST", "Line " + i.lineNo() + " has a bad product, quantity or price");
        }
        var t = totals(r.items(), r.taxRate());
        if (!same(t.subtotal, r.subtotalAmount()) || !same(t.tax, r.taxAmount()) || !same(t.total, r.totalAmount()))
            throw OfflineOrderException.invalid("TOTALS_MISMATCH",
                "Totals do not add up: expected subtotal " + t.subtotal + ", tax " + t.tax + ", total " + t.total);

        List<OfflineOrderRequest.Payment> payments = r.payments() == null ? List.of() : r.payments();
        Set<String> ids = new HashSet<>();
        for (var p : payments) {
            if (p.paymentId() == null || !UUID_FORMAT.matcher(p.paymentId()).matches() || !ids.add(p.paymentId()))
                throw OfflineOrderException.invalid("INVALID_REQUEST", "Each payment needs its own paymentId (UUID)");
            if (p.provider() == null || p.provider().isBlank() || p.provider().length() > 50)
                throw OfflineOrderException.invalid("INVALID_REQUEST", "Payment provider is required (up to 50 characters)");
            if (!"PAID".equals(p.outcome()))
                throw OfflineOrderException.invalid("INVALID_REQUEST", "Only successful (PAID) payments are sent");
            if (p.providerReference() != null && p.providerReference().length() > 255)
                throw OfflineOrderException.invalid("INVALID_REQUEST", "providerReference is too long");
            if (p.authCode() != null && p.authCode().length() > 20)
                throw OfflineOrderException.invalid("INVALID_REQUEST", "authCode is up to 20 characters");
        }
        if ("PAID".equals(r.status()) && payments.isEmpty())
            throw OfflineOrderException.invalid("PAYMENT_MISSING", "A paid order needs its payment");
    }

    record Totals(BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<BigDecimal> lineTax) {}

    // Same rule as the server's own pricing: per-line tax rounded half-up, lines add up to the order.
    static Totals totals(List<OfflineOrderRequest.Item> items, BigDecimal rate) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        List<BigDecimal> lineTax = new ArrayList<>();
        for (var i : items) {
            BigDecimal net = i.unitPrice().multiply(BigDecimal.valueOf(i.quantity()));
            BigDecimal lt = net.multiply(rate).setScale(2, RoundingMode.HALF_UP);
            subtotal = subtotal.add(net);
            tax = tax.add(lt);
            lineTax.add(lt);
        }
        subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        return new Totals(subtotal, tax, subtotal.add(tax), lineTax);
    }

    static Action decide(String existing, String requested) {
        boolean existingOpen = "CREATED".equals(existing) || "PENDING".equals(existing);
        boolean requestedOpen = "CREATED".equals(requested) || "PENDING".equals(requested);
        if (existingOpen && "PAID".equals(requested)) return Action.MARK_PAID;
        if (existingOpen && "CANCELLED".equals(requested)) return Action.MARK_CANCELLED;
        if ("CANCELLED".equals(existing) && "PAID".equals(requested)) return Action.CANCELLED_CONFLICT;
        // Same state, or an older copy of the order arriving late: keep what the server has.
        return Action.NOTHING;
    }

    private static boolean same(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }

    // ── saving (one transaction) ──────────────────────────────────────────────

    private Outcome save(OfflineOrderRequest r, long orgId, StoreConfig store, DeviceRepository.SyncInfo device) {
        Map<Long, BigDecimal> prices = productPrices(r, orgId);
        boolean priceDiffers = r.taxRate().compareTo(store.vatRate()) != 0
            || r.items().stream().anyMatch(i -> prices.get(i.productId()).compareTo(i.unitPrice()) != 0);

        List<Map<String, Object>> found = jdbc.queryForList(
            "SELECT store_id, display_id, status, version, subtotal_amount, tax_amount, total_amount " +
            "FROM orders WHERE id = ? FOR UPDATE", r.orderId());

        if (found.isEmpty()) {
            return insertNew(r, priceDiffers, device);
        }
        Map<String, Object> o = found.get(0);
        if (((Number) o.get("store_id")).longValue() != r.storeId())
            throw OfflineOrderException.conflict("STORE_MISMATCH", "This order already exists in another store");
        Object existingDisplay = o.get("display_id");
        if (!same((BigDecimal) o.get("total_amount"), r.totalAmount()) || !linesMatch(r))
            throw OfflineOrderException.conflict("ITEMS_CONFLICT", "The order already exists with different lines or totals");

        String existingStatus = (String) o.get("status");
        int version = ((Number) o.get("version")).intValue();
        Action action = decide(existingStatus, r.status());
        if (action == Action.CANCELLED_CONFLICT)
            throw OfflineOrderException.conflict("ORDER_CANCELLED_ON_SERVER", "The server holds this order as cancelled");

        boolean wasChanged = false;
        boolean becamePaid = false;
        long displayId;
        if (existingDisplay == null) {
            displayId = orderRepository.nextDisplayId(r.storeId());
            jdbc.update("UPDATE orders SET display_id = ? WHERE id = ? AND display_id IS NULL", displayId, r.orderId());
            wasChanged = true;
        } else {
            displayId = ((Number) existingDisplay).longValue();
        }
        String reference = jdbc.queryForObject("SELECT order_reference FROM orders WHERE id = ?", String.class, r.orderId());
        if (reference == null) {
            reference = referenceFor(r, device);
            jdbc.update("UPDATE orders SET order_reference = ? WHERE id = ?", reference, r.orderId());
            wasChanged = true;
        }
        int added = insertPayments(r);
        if (action == Action.MARK_PAID) {
            jdbc.update("UPDATE orders SET status = 'PAID', payment_reference = ?, version = ?, offline_pushed = 1, " +
                "price_differs = GREATEST(price_differs, ?) WHERE id = ?",
                paymentReference(r), version + 1, priceDiffers ? 1 : 0, r.orderId());
            history(r.orderId(), "PAID", "offline");
            wasChanged = true;
            becamePaid = true;
        } else if (action == Action.MARK_CANCELLED) {
            jdbc.update("UPDATE orders SET status = 'CANCELLED', version = ?, offline_pushed = 1 WHERE id = ?", version + 1, r.orderId());
            history(r.orderId(), "CANCELLED", "offline");
            wasChanged = true;
        }
        return new Outcome(wasChanged || added > 0 ? "UPDATED" : "ALREADY_SYNCED", becamePaid, displayId, reference);
    }

    private Outcome insertNew(OfflineOrderRequest r, boolean priceDiffers, DeviceRepository.SyncInfo device) {
        long displayId = orderRepository.nextDisplayId(r.storeId());
        String reference = referenceFor(r, device);
        boolean paid = "PAID".equals(r.status());
        Instant created = sensibleCreatedAt(r.createdAt());
        jdbc.update("INSERT INTO orders (id, display_id, store_id, status, currency, tax_rate, username, subtotal_amount, " +
            "tax_amount, total_amount, payment_reference, version, created_at, offline_pushed, price_differs, order_reference, device_id) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,?)",
            r.orderId(), displayId, r.storeId(), "PENDING".equals(r.status()) ? "CREATED" : r.status(), r.currency(),
            r.taxRate(), r.username(), r.subtotalAmount(), r.taxAmount(), r.totalAmount(),
            paid ? paymentReference(r) : null, "CREATED".equals(r.status()) || "PENDING".equals(r.status()) ? 0 : 1,
            Timestamp.from(created), priceDiffers ? 1 : 0, reference, device.id());

        var t = totals(r.items(), r.taxRate());
        List<OfflineOrderRequest.Item> items = r.items();
        jdbc.batchUpdate("INSERT INTO order_items (order_id, line_no, product_id, quantity, unit_price, discount_amount, tax_amount) " +
            "VALUES (?,?,?,?,?,0,?)", new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            public void setValues(java.sql.PreparedStatement ps, int n) throws java.sql.SQLException {
                var i = items.get(n);
                ps.setString(1, r.orderId());
                ps.setInt(2, i.lineNo());
                ps.setLong(3, i.productId());
                ps.setInt(4, i.quantity());
                ps.setBigDecimal(5, i.unitPrice());
                ps.setBigDecimal(6, t.lineTax().get(n));
            }
            public int getBatchSize() { return items.size(); }
        });

        history(r.orderId(), "CREATED", null);
        insertPayments(r);
        if (paid) history(r.orderId(), "PAID", "offline");
        else if ("CANCELLED".equals(r.status())) history(r.orderId(), "CANCELLED", "offline");
        return new Outcome("CREATED", paid, displayId, reference);
    }

    // Every product must exist in the store's organization; returns today's price of each.
    private Map<Long, BigDecimal> productPrices(OfflineOrderRequest r, long orgId) {
        Set<Long> ids = r.items().stream().map(OfflineOrderRequest.Item::productId).collect(Collectors.toSet());
        String in = ids.stream().map(x -> "?").collect(Collectors.joining(","));
        List<Object> args = new ArrayList<>(ids);
        args.add(orgId);
        Map<Long, BigDecimal> prices = new HashMap<>();
        jdbc.query("SELECT id, price FROM products WHERE id IN (" + in + ") AND company_id = ?",
            rs -> { prices.put(rs.getLong("id"), rs.getBigDecimal("price")); }, args.toArray());
        for (Long id : ids) {
            if (!prices.containsKey(id))
                throw OfflineOrderException.invalid("PRODUCT_NOT_FOUND", "Product " + id + " does not exist");
        }
        return prices;
    }

    // The lines already stored must be exactly the lines sent: lines never change after creation.
    private boolean linesMatch(OfflineOrderRequest r) {
        List<Map<String, Object>> stored = jdbc.queryForList(
            "SELECT line_no, product_id, quantity, unit_price FROM order_items WHERE order_id = ? ORDER BY line_no", r.orderId());
        if (stored.size() != r.items().size()) return false;
        Map<Integer, OfflineOrderRequest.Item> sent = r.items().stream()
            .collect(Collectors.toMap(OfflineOrderRequest.Item::lineNo, i -> i));
        for (Map<String, Object> s : stored) {
            var i = sent.get(((Number) s.get("line_no")).intValue());
            if (i == null || i.productId() != ((Number) s.get("product_id")).longValue()
                || i.quantity() != ((Number) s.get("quantity")).intValue()
                || !same(i.unitPrice(), (BigDecimal) s.get("unit_price"))) return false;
        }
        return true;
    }

    // Records the payments that are not stored yet; returns how many were added.
    private int insertPayments(OfflineOrderRequest r) {
        int added = 0;
        if (r.payments() == null) return 0;
        for (var p : r.payments()) {
            List<Map<String, Object>> byId = jdbc.queryForList(
                "SELECT order_id, provider, provider_reference FROM payments WHERE id = ?", p.paymentId());
            if (!byId.isEmpty()) {
                var e = byId.get(0);
                if (!r.orderId().equals(e.get("order_id")) || !Objects.equals(p.provider(), e.get("provider"))
                    || !Objects.equals(p.providerReference(), e.get("provider_reference")))
                    throw OfflineOrderException.conflict("PAYMENT_CONFLICT", "Payment " + p.paymentId() + " exists with different details");
                continue;
            }
            if (p.providerReference() != null) {
                Integer same = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM payments WHERE order_id = ? AND provider = ? AND provider_reference = ?",
                    Integer.class, r.orderId(), p.provider(), p.providerReference());
                if (same != null && same > 0) continue; // the same terminal payment, already recorded
            }
            Timestamp at = Timestamp.from(sensibleCreatedAt(p.createdAt() != null ? p.createdAt() : r.createdAt()));
            jdbc.update("INSERT INTO payments (id, order_id, provider, outcome, provider_reference, detail, created_at) " +
                "VALUES (?,?,?,'PAID',?,?,?)", p.paymentId(), r.orderId(), p.provider(), p.providerReference(), p.authCode(), at);
            if (p.authCode() != null || (p.notes() != null && !p.notes().isNull()) || Boolean.TRUE.equals(p.lateApproval())) {
                insertConfirmedAttempt(r.orderId(), p, at);
            }
            added++;
        }
        return added;
    }

    // Same audit trail an online terminal payment leaves: a confirmed attempt with the approval code and notes.
    private void insertConfirmedAttempt(String orderId, OfflineOrderRequest.Payment p, Timestamp at) {
        ObjectNode notes = p.notes() != null && p.notes().isObject() ? ((ObjectNode) p.notes()).deepCopy() : mapper.createObjectNode();
        if (Boolean.TRUE.equals(p.lateApproval())) notes.put("late_approval", true);
        String reference = p.providerReference() != null ? p.providerReference() : p.paymentId();
        jdbc.update("INSERT INTO payment_attempts (id, order_id, provider, provider_reference, redirect_url, expires_at, created_at, " +
            "status, auth_code, notes) VALUES (?,?,?,?,'',?,?,'CONFIRMED',?,?)",
            p.paymentId(), orderId, p.provider(), reference, at, at, p.authCode(), notes.isEmpty() ? null : notes.toString());
    }

    private static String paymentReference(OfflineOrderRequest r) {
        if (r.payments() == null || r.payments().isEmpty()) return null;
        var p = r.payments().get(0);
        return p.providerReference() != null ? p.providerReference() : p.paymentId();
    }

    private void history(String orderId, String status, String subStatus) {
        jdbc.update("INSERT INTO order_status_history (id, order_id, status, sub_status) VALUES (?,?,?,?)",
            UUID.randomUUID().toString(), orderId, status, subStatus);
    }

    // The reference the kiosk sent, or one made here in the same shape: <prefix>-<device no>-<serial>.
    // The serial continues from the device's latest order and goes back to 000 after 999.
    private String referenceFor(OfflineOrderRequest r, DeviceRepository.SyncInfo device) {
        if (r.orderReference() != null && !r.orderReference().isBlank()) return r.orderReference().trim();
        String prefix = jdbc.query("SELECT short_code FROM stores WHERE id = ?",
            rs -> rs.next() ? rs.getString(1) : null, r.storeId());
        if (prefix == null || prefix.isBlank()) prefix = String.valueOf(r.storeId());
        Integer last = lastReferenceSerial(device.id());
        return formatReference(prefix, device.deviceNo(), last == null ? 0 : nextSerial(last));
    }

    // The serial (last digits) of the newest order of this device that has a reference, or null when none.
    public Integer lastReferenceSerial(long deviceId) {
        List<String> refs = jdbc.queryForList(
            "SELECT order_reference FROM orders WHERE device_id = ? AND order_reference IS NOT NULL " +
            "ORDER BY created_at DESC, display_id DESC LIMIT 1", String.class, deviceId);
        return refs.isEmpty() ? null : serialOf(refs.get(0));
    }

    static Integer serialOf(String reference) {
        var m = java.util.regex.Pattern.compile("(\\d{1,6})$").matcher(reference);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static int nextSerial(int last) {
        return last >= 999 ? 0 : last + 1;
    }

    static String formatReference(String prefix, int deviceNo, int serial) {
        return prefix + "-" + String.format("%02d", deviceNo) + "-" + String.format("%03d", serial);
    }

    // A device clock can be wrong: never store a time in the future.
    private static Instant sensibleCreatedAt(Instant at) {
        Instant now = Instant.now();
        if (at == null || at.isAfter(now.plus(1, ChronoUnit.HOURS))) return now;
        return at;
    }
}
