package com.waha.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.order.dto.OrderItemRequest;
import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.payment.dto.PaymentRecord;
import com.waha.product.Product;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlOutParameter;
import org.springframework.jdbc.core.simple.SimpleJdbcCall;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

// JDBC-direct, no JPA - same pattern as commerce-platform's OrderRepository.
// order_id columns are CHAR(36) throughout now (client-generated UUID), not
// auto-increment BIGINT - see V1__core_schema.sql for why.
@Repository
public class OrderRepository {

    private final SimpleJdbcCall createOrderCall;
    private final SimpleJdbcCall markPaidCall;
    private final SimpleJdbcInsert orderItemsInsert;
    private final SimpleJdbcInsert paymentsInsert;
    private final SimpleJdbcInsert paymentAttemptsInsert;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final com.waha.config.ConfigService configService;

    public OrderRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                           com.waha.config.ConfigService configService) {
        this.configService = configService;
        this.objectMapper = objectMapper;
        this.jdbcTemplate = jdbcTemplate;

        this.createOrderCall = new SimpleJdbcCall(jdbcTemplate)
            .withProcedureName("sp_create_order_idempotent")
            .declareParameters(
                new SqlOutParameter("p_was_created", Types.BOOLEAN)
            );

        this.markPaidCall = new SimpleJdbcCall(jdbcTemplate)
            .withProcedureName("sp_mark_order_paid")
            .declareParameters(
                new SqlOutParameter("p_success", Types.BOOLEAN)
            );

        this.orderItemsInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("order_items")
            .usingColumns("order_id", "product_id", "quantity", "unit_price");

        this.paymentsInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("payments")
            .usingColumns("id", "order_id", "provider", "outcome", "provider_reference", "detail");

        this.paymentAttemptsInsert = new SimpleJdbcInsert(jdbcTemplate)
            .withTableName("payment_attempts")
            .usingColumns("id", "order_id", "provider", "provider_reference", "redirect_url", "qr_data_uri", "expires_at");
    }

    public record OrderPaymentInfo(String status, int version, BigDecimal totalAmount, String currency, long storeId) {}

    public OrderPaymentInfo getPaymentInfo(String orderId) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT status, version, total_amount, currency, store_id FROM orders WHERE id = ?",
                (rs, i) -> new OrderPaymentInfo(rs.getString("status"), rs.getInt("version"), rs.getBigDecimal("total_amount"), rs.getString("currency"), rs.getLong("store_id")),
                orderId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new OrderNotFoundException("Order " + orderId + " not found");
        }
    }

    // orderId is client-supplied (see CreateOrderRequest). Returns
    // wasCreated=false as a safe no-op if that id already exists - the
    // idempotent-retry / offline-replay contract.
    public boolean createOrderIdempotent(String orderId, long storeId, String currency, BigDecimal taxRate, String username, BigDecimal subtotal, BigDecimal tax) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_id", orderId);
        params.put("p_store_id", storeId);
        params.put("p_currency", currency);
        params.put("p_tax_rate", taxRate);
        params.put("p_username", username);
        params.put("p_subtotal_amount", subtotal);
        params.put("p_tax_amount", tax);

        Map<String, Object> result = createOrderCall.execute(params);
        return (Boolean) result.get("p_was_created");
    }

    // Only called when wasCreated=true - an idempotent hit means items were
    // already inserted the first time, inserting again would duplicate them.
    public void insertOrderItems(String orderId, List<OrderItemRequest> items, Map<Long, Product> productsById) {
        for (OrderItemRequest item : items) {
            Product p = productsById.get(item.productId());
            Map<String, Object> row = new HashMap<>();
            row.put("order_id", orderId);
            row.put("product_id", p.id());
            row.put("quantity", item.quantity());
            row.put("unit_price", p.price());
            orderItemsInsert.execute(row);
        }
    }

    public boolean markPaid(String orderId, int expectedVersion, String paymentReference, String subStatus) {
        Map<String, Object> params = new HashMap<>();
        params.put("p_order_id", orderId);
        params.put("p_expected_version", expectedVersion);
        params.put("p_payment_reference", paymentReference);
        params.put("p_sub_status", subStatus);

        Map<String, Object> result = markPaidCall.execute(params);
        return (Boolean) result.get("p_success");
    }

    // Audit trail for finalized payment outcomes (PAID from simulated/terminal,
    // FAILED from any provider). Redirect-based sessions write their PENDING
    // record to payment_attempts instead — see recordPendingAttempt.
    public void recordPayment(String orderId, String provider, String outcome, String providerReference, String detail) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("order_id", orderId);
        row.put("provider", provider);
        row.put("outcome", outcome);
        row.put("provider_reference", providerReference);
        row.put("detail", detail);
        paymentsInsert.execute(row);
    }

    // Records an in-flight redirect/QR session in the hot payment_attempts table.
    // Lives there until the webhook confirms PAID (then cloned to payments and
    // removed), or until it expires and is cleared by the weekly cleanup.
    public void recordPendingAttempt(String orderId, String provider, String providerReference,
                                     String redirectUrl, String qrDataUri, Instant expiresAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", UUID.randomUUID().toString());
        row.put("order_id", orderId);
        row.put("provider", provider);
        row.put("provider_reference", providerReference);
        row.put("redirect_url", redirectUrl != null ? redirectUrl : "");
        row.put("qr_data_uri", qrDataUri);
        row.put("expires_at", Timestamp.from(expiresAt));
        paymentAttemptsInsert.execute(row);
    }

    // Deletes the in-flight attempt record after it is resolved (paid or
    // otherwise). Call this after writing the final outcome to payments.
    public void removePaymentAttempt(String providerReference) {
        jdbcTemplate.update(
            "DELETE FROM payment_attempts WHERE provider_reference = ?",
            providerReference
        );
    }

    // The PENDING attempt written when a checkout session is created.
    // provider_reference is OUR stored reference (e.g. MyFatoorah InvoiceId),
    // not the paymentId the redirect URL carries — those differ for MyFatoorah.
    public record PendingPayment(String provider, String providerReference) {}

    public Optional<PendingPayment> findPendingPayment(String orderId) {
        List<PendingPayment> results = jdbcTemplate.query(
            "SELECT provider, provider_reference FROM payment_attempts WHERE order_id = ? ORDER BY created_at DESC LIMIT 1",
            (rs, i) -> new PendingPayment(rs.getString("provider"), rs.getString("provider_reference")),
            orderId
        );
        return results.stream().findFirst();
    }

    public record OrderIdAndProvider(String orderId, String provider) {}

    // A webhook arrives with the provider's own reference — look it up in the
    // in-flight table to map back to our order.
    public Optional<OrderIdAndProvider> findOrderIdByProviderReference(String providerReference) {
        List<OrderIdAndProvider> results = jdbcTemplate.query(
            "SELECT order_id, provider FROM payment_attempts WHERE provider_reference = ? LIMIT 1",
            (rs, i) -> new OrderIdAndProvider(rs.getString("order_id"), rs.getString("provider")),
            providerReference
        );
        return results.stream().findFirst();
    }

    // Atomically allocates the next display_id for a store using the
    // LAST_INSERT_ID() trick: after the INSERT/ON DUPLICATE KEY UPDATE,
    // LAST_INSERT_ID() returns the value that was SET inside the expression,
    // so we never need a separate SELECT to read back the counter.
    public long nextDisplayId(long storeId) {
        jdbcTemplate.update(
            "INSERT INTO store_order_sequences (store_id, `last_value`) VALUES (?, LAST_INSERT_ID(1)) " +
            "ON DUPLICATE KEY UPDATE `last_value` = LAST_INSERT_ID(`last_value` + 1)",
            storeId
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    public void setDisplayId(String orderId, long displayId) {
        jdbcTemplate.update(
            "UPDATE orders SET display_id = ? WHERE id = ? AND display_id IS NULL",
            displayId, orderId
        );
    }

    public OrderResponse getOrderDetail(String orderId, boolean wasCreated) {
        Map<String, Object> header;
        try {
            header = jdbcTemplate.queryForMap(
                "SELECT o.display_id, o.store_id, o.status, o.subtotal_amount, o.tax_amount, o.total_amount, " +
                "o.payment_reference, o.currency, o.tax_rate, o.username, o.created_at, " +
                "(SELECT p.provider FROM payments p WHERE p.order_id = o.id AND p.outcome = 'PAID' ORDER BY p.attempted_at DESC LIMIT 1) AS payment_method " +
                "FROM orders o WHERE o.id = ?",
                orderId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new OrderNotFoundException("Order " + orderId + " not found");
        }

        List<OrderItemView> items = jdbcTemplate.query(
            "SELECT oi.product_id, p.barcode, p.name, oi.quantity, oi.unit_price, (oi.quantity * oi.unit_price) AS line_total " +
            "FROM order_items oi JOIN products p ON oi.product_id = p.id WHERE oi.order_id = ?",
            (rs, i) -> new OrderItemView(
                rs.getLong("product_id"), rs.getString("barcode"), parseJson(rs.getString("name")),
                rs.getInt("quantity"), rs.getBigDecimal("unit_price"), rs.getBigDecimal("line_total")
            ), orderId
        );

        // Exclude PENDING rows that were later resolved (same providerReference has a non-PENDING row).
        // PENDING with no follow-up (abandoned sessions) are also excluded from invoice display —
        // they're audit data, not customer-facing payment history.
        List<PaymentRecord> payments = jdbcTemplate.query(
            "SELECT provider, outcome, provider_reference, detail, attempted_at " +
            "FROM payments WHERE order_id = ? AND outcome != 'PENDING' ORDER BY attempted_at DESC",
            (rs, i) -> new PaymentRecord(
                rs.getString("provider"), rs.getString("outcome"),
                rs.getString("provider_reference"), rs.getString("detail"),
                rs.getTimestamp("attempted_at").toInstant()
            ), orderId
        );

        Long displayId = (Long) header.get("display_id");
        long storeId = ((Number) header.get("store_id")).longValue();
        java.sql.Timestamp createdTs = (java.sql.Timestamp) header.get("created_at");
        java.time.Instant createdAt = createdTs != null ? createdTs.toInstant() : null;

        return new OrderResponse(
            orderId, displayId, storeId, wasCreated, (String) header.get("status"), (String) header.get("username"),
            (BigDecimal) header.get("subtotal_amount"), (BigDecimal) header.get("tax_amount"),
            (BigDecimal) header.get("total_amount"), (BigDecimal) header.get("tax_rate"),
            (String) header.get("currency"), (String) header.get("payment_reference"),
            (String) header.get("payment_method"),
            configService.getPublicBaseUrl() + "/api/invoices/" + orderId,
            createdAt,
            items,
            payments
        );
    }

    // Order history for a logged-in user. Returns summary rows (no items)
    // ordered newest-first. Page size capped by caller.
    public record OrderSummary(String orderId, Long displayId, String status, String paymentMethod, BigDecimal total, String currency, String invoiceUrl, java.time.Instant createdAt) {}

    public List<OrderSummary> findByUsername(String username, long storeId, int page, int size) {
        return jdbcTemplate.query(
            "SELECT o.id, o.display_id, o.status, o.total_amount, o.currency, o.created_at, " +
            "(SELECT p.provider FROM payments p WHERE p.order_id = o.id AND p.outcome = 'PAID' ORDER BY p.attempted_at DESC LIMIT 1) AS payment_method " +
            "FROM orders o WHERE o.username = ? AND o.store_id = ? ORDER BY o.created_at DESC LIMIT ? OFFSET ?",
            (rs, i) -> {
                long did = rs.getLong("display_id");
                Long displayId = rs.wasNull() ? null : did;
                return new OrderSummary(
                    rs.getString("id"), displayId, rs.getString("status"),
                    rs.getString("payment_method"),
                    rs.getBigDecimal("total_amount"), rs.getString("currency"),
                    configService.getPublicBaseUrl() + "/api/invoices/" + rs.getString("id"),
                    rs.getTimestamp("created_at").toInstant()
                );
            },
            username, storeId, size, page * size
        );
    }

    // Returns true if the order can be cancelled by the user:
    //   - exists with the given username (ownership check)
    //   - status is CREATED (not already paid or pending)
    //   - no PENDING payment row (an external redirect is in-flight)
    public boolean cancelOrder(String orderId, String username) {
        // Ownership + status check
        List<String> rows = jdbcTemplate.query(
            "SELECT id FROM orders WHERE id = ? AND username = ? AND status = 'CREATED'",
            (rs, i) -> rs.getString("id"), orderId, username
        );
        if (rows.isEmpty()) return false;

        // Block only if a non-expired attempt is in-flight
        Integer pendingCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payment_attempts WHERE order_id = ? AND expires_at > NOW()",
            Integer.class, orderId
        );
        if (pendingCount != null && pendingCount > 0) return false;

        jdbcTemplate.update("DELETE FROM order_items WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM orders WHERE id = ?", orderId);
        return true;
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
