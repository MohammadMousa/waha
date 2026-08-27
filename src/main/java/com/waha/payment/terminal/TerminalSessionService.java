package com.waha.payment.terminal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.common.InvalidRequestException;
import com.waha.order.OrderNotPayableException;
import com.waha.order.OrderRepository;
import com.waha.payment.PaymentSseRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public TerminalSessionService(JdbcTemplate jdbc,
                                  OrderRepository orderRepository,
                                  PaymentSseRegistry sseRegistry,
                                  ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.orderRepository = orderRepository;
        this.sseRegistry = sseRegistry;
        this.objectMapper = objectMapper;
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
        orderRepository.markPaid(attempt.orderId(), info.version(), id, "terminal");
        sseRegistry.notifyPaid(attempt.orderId());
        log.info("Terminal confirmed — attempt={} order={}", id, attempt.orderId());
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
