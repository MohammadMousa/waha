package com.waha.payment.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.common.InvalidRequestException;
import com.waha.order.OrderNotPayableException;
import com.waha.order.OrderPaidEvent;
import com.waha.order.OrderRepository;
import com.waha.payment.PaymentSseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class TerminalSessionService {

    private static final Logger log = LoggerFactory.getLogger(TerminalSessionService.class);
    private static final int TIMEOUT_SECONDS = 90;

    private final JdbcTemplate jdbc;
    private final OrderRepository orderRepository;
    private final PaymentSseRegistry sseRegistry;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public TerminalSessionService(JdbcTemplate jdbc,
                                  OrderRepository orderRepository,
                                  PaymentSseRegistry sseRegistry,
                                  ObjectMapper objectMapper,
                                  ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.orderRepository = orderRepository;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    // Creates a PENDING terminal attempt in payment_attempts.
    // provider_reference = attempt id (self-referential; terminal has no external ref at creation).
    public TerminalAttemptView create(String orderId) {
        var info = orderRepository.getPaymentInfo(orderId);
        if ("PAID".equals(info.status())) {
            throw new OrderNotPayableException("Order " + orderId + " is already paid");
        }
        String id = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(TIMEOUT_SECONDS, ChronoUnit.SECONDS);

        jdbc.update("""
            INSERT INTO payment_attempts
              (id, order_id, provider, provider_reference, redirect_url, expires_at, status)
            VALUES (?, ?, 'terminal', ?, '', ?, 'PENDING')
            """,
            id, orderId, id, Timestamp.from(expiresAt)
        );
        return new TerminalAttemptView(id, orderId, info.totalAmount(), info.currency(), "PENDING");
    }

    // Terminal app polls this — returns the oldest PENDING terminal attempt, no store filter.
    public Optional<TerminalAttemptView> findPending() {
        List<TerminalAttemptView> rows = jdbc.query("""
            SELECT pa.id, pa.order_id, o.total_amount, o.currency, pa.status
            FROM payment_attempts pa
            JOIN orders o ON o.id = pa.order_id
            WHERE pa.provider = 'terminal' AND pa.status = 'PENDING'
            ORDER BY pa.created_at ASC
            LIMIT 1
            """,
            (rs, n) -> new TerminalAttemptView(
                rs.getString("id"),
                rs.getString("order_id"),
                rs.getBigDecimal("total_amount"),
                rs.getString("currency"),
                rs.getString("status")
            )
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public TerminalAttemptView getById(String id) {
        try {
            return jdbc.queryForObject("""
                SELECT pa.id, pa.order_id, o.total_amount, o.currency, pa.status
                FROM payment_attempts pa
                JOIN orders o ON o.id = pa.order_id
                WHERE pa.id = ?
                """,
                (rs, n) -> new TerminalAttemptView(
                    rs.getString("id"),
                    rs.getString("order_id"),
                    rs.getBigDecimal("total_amount"),
                    rs.getString("currency"),
                    rs.getString("status")
                ),
                id
            );
        } catch (EmptyResultDataAccessException e) {
            throw new InvalidRequestException("Terminal session not found: " + id);
        }
    }

    // Called when NFC card tap is read. Marks attempt CONFIRMED, marks order PAID.
    public void confirm(String id, String authCode, Map<String, Object> notes) {
        TerminalAttemptView attempt = getById(id);
        if (!"PENDING".equals(attempt.status())) {
            throw new InvalidRequestException("Session is not pending (status=" + attempt.status() + ")");
        }

        String notesJson = null;
        if (notes != null && !notes.isEmpty()) {
            try { notesJson = objectMapper.writeValueAsString(notes); } catch (Exception ignored) {}
        }

        int updated = jdbc.update(
            "UPDATE payment_attempts SET status='CONFIRMED', auth_code=?, notes=? WHERE id=? AND status='PENDING'",
            authCode, notesJson, id
        );
        if (updated == 0) {
            throw new InvalidRequestException("Session could not be confirmed — may have timed out concurrently");
        }

        var info = orderRepository.getPaymentInfo(attempt.orderId());
        orderRepository.recordPayment(attempt.orderId(), "terminal", "PAID", id, authCode);
        boolean paid = orderRepository.markPaid(attempt.orderId(), info.version(), id, "terminal");
        sseRegistry.notifyPaid(attempt.orderId());
        log.info("Terminal confirmed — attempt={} order={}", id, attempt.orderId());
        if (paid) {
            eventPublisher.publishEvent(new OrderPaidEvent(attempt.orderId(), info.storeId(), info.currency()));
        }
    }

    public record LateResult(String result) {}

    // A card approval that reached the terminal after the kiosk gave up. Pays the order only when it is still
    // CREATED, younger than late_approval_expiration_minutes (counted from order creation, server clock), and
    // the amount matches. Anything else is ignored and the order is left as it is.
    public LateResult lateApproval(String orderId, long orgId, BigDecimal amount, String approvalCode,
                                   String rrn, String terminalId) {
        String detail = "order=" + orderId + " amount=" + amount + " approvalCode=" + approvalCode
            + " rrn=" + rrn + " terminalId=" + terminalId;

        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT status, version, total_amount, currency, store_id, organization_id,
                   TIMESTAMPDIFF(SECOND, created_at, NOW()) AS age_seconds
            FROM orders WHERE id = ?
            """, orderId);
        if (rows.isEmpty() || ((Number) rows.get(0).get("organization_id")).longValue() != orgId) {
            log.warn("Late approval ignored (order not found) {}", detail);
            return new LateResult("IGNORED");
        }
        Map<String, Object> o = rows.get(0);

        Integer already = jdbc.queryForObject(
            "SELECT COUNT(*) FROM payments WHERE order_id = ? AND provider = 'terminal' AND provider_reference = ?",
            Integer.class, orderId, rrn);
        if (already != null && already > 0) {
            log.info("Late approval repeated, already recorded {}", detail);
            return new LateResult("PAID");
        }

        long limitSeconds = lateApprovalMinutes(orgId) * 60L;
        long age = ((Number) o.get("age_seconds")).longValue();
        BigDecimal total = (BigDecimal) o.get("total_amount");
        if (!"CREATED".equals(o.get("status"))) {
            log.warn("Late approval ignored (order status {}) {}", o.get("status"), detail);
            return new LateResult("IGNORED");
        }
        if (age >= limitSeconds) {
            log.warn("Late approval ignored (order {}s old, limit {}s) {}", age, limitSeconds, detail);
            return new LateResult("IGNORED");
        }
        if (total.compareTo(amount) != 0) {
            log.warn("Late approval ignored (order total {}) {}", total, detail);
            return new LateResult("IGNORED");
        }

        int version = ((Number) o.get("version")).intValue();
        boolean paid = orderRepository.markPaid(orderId, version, rrn, "terminal-late");
        if (!paid) {
            log.warn("Late approval ignored (order changed meanwhile) {}", detail);
            return new LateResult("IGNORED");
        }
        orderRepository.recordPayment(orderId, "terminal", "PAID", rrn, approvalCode);

        // Mark the order's latest terminal attempt (status stays as it is).
        List<String> attempts = jdbc.queryForList(
            "SELECT id FROM payment_attempts WHERE order_id = ? AND provider = 'terminal' ORDER BY created_at DESC LIMIT 1",
            String.class, orderId);
        if (!attempts.isEmpty()) {
            Map<String, Object> notes = new java.util.LinkedHashMap<>();
            notes.put("late_approval", true);
            notes.put("rrn", rrn);
            notes.put("terminalId", terminalId);
            notes.put("amount", amount);
            try {
                jdbc.update("UPDATE payment_attempts SET auth_code = ?, notes = JSON_MERGE_PATCH(COALESCE(notes, JSON_OBJECT()), ?) WHERE id = ?",
                    approvalCode, objectMapper.writeValueAsString(notes), attempts.get(0));
            } catch (Exception e) {
                log.warn("Late approval: could not annotate attempt: {}", e.getMessage());
            }
        }

        sseRegistry.notifyPaid(orderId);
        eventPublisher.publishEvent(new OrderPaidEvent(orderId, ((Number) o.get("store_id")).longValue(), (String) o.get("currency")));
        log.info("Late approval paid the order {}", detail);
        return new LateResult("PAID");
    }

    private int lateApprovalMinutes(long orgId) {
        try {
            String v = jdbc.query(
                "SELECT value FROM system_properties WHERE `key` = 'late_approval_expiration_minutes' " +
                "AND organization_id IN (0, ?) ORDER BY organization_id DESC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, orgId);
            if (v != null) {
                int m = Integer.parseInt(v.trim());
                if (m >= 1 && m <= 60) return m;
            }
        } catch (RuntimeException ignored) { }
        return 3;
    }

    public void cancel(String id) {
        int updated = jdbc.update(
            "UPDATE payment_attempts SET status='CANCELLED' WHERE id=? AND status='PENDING'",
            id
        );
        if (updated > 0) log.info("Terminal cancelled — attempt={}", id);
    }

    // Runs every 30s — marks expired PENDING terminal attempts as TIMEOUT.
    @Scheduled(fixedDelay = 30_000)
    public void timeoutStale() {
        int count = jdbc.update("""
            UPDATE payment_attempts
            SET status = 'TIMEOUT'
            WHERE provider = 'terminal'
              AND status = 'PENDING'
              AND expires_at < NOW()
            """);
        if (count > 0) log.info("Terminal timeout job: {} session(s) timed out", count);
    }
}
