package com.waha.payment.method;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class PaymentMethodRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public PaymentMethodRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // Returns all payment methods available to a store in a given mode.
    // A method is available when:
    //   1. globally active (payment_methods.active = TRUE)
    //   2. the mode is in its available_modes SET
    //   3. no store override exists, OR the store's override is active
    //
    // Sort order: store's override sort_order wins if set; falls back to
    // the global sort_order.
    public List<PaymentMethodSummary> findForStore(long storeId, String mode) {
        return jdbcTemplate.query(
            "SELECT pm.id, pm.key, pm.display_name, pm.provider, pm.offline_capable, pm.payment_url, " +
            "       COALESCE(pms.sort_order, pm.sort_order) AS effective_sort_order " +
            "FROM payment_methods pm " +
            "LEFT JOIN payment_methods_store pms " +
            "       ON pms.payment_method_id = pm.id AND pms.store_id = ? " +
            "WHERE pm.active = TRUE " +
            "  AND FIND_IN_SET(?, pm.available_modes) > 0 " +
            "  AND (pms.id IS NULL OR pms.active = TRUE) " +
            "  AND (pm.provider != 'PAYMENT_URL' OR (pm.payment_url IS NOT NULL AND pm.payment_url != '')) " +
            "ORDER BY effective_sort_order, pm.id",
            (rs, i) -> new PaymentMethodSummary(
                rs.getLong("id"),
                rs.getString("key"),
                parseJsonOrNull(rs.getString("display_name")),
                rs.getString("provider"),
                rs.getBoolean("offline_capable"),
                rs.getInt("effective_sort_order"),
                rs.getString("payment_url")
            ),
            storeId, mode
        );
    }

    // Admin view: all globally-active methods, with per-store enabled state.
    // storeActive = null means no override (inherits global active=true).
    // Used by the admin payment-methods management panel in Settings.
    public List<AdminPaymentMethodView> findAllForAdmin(long storeId) {
        return jdbcTemplate.query(
            "SELECT pm.id, pm.key, pm.display_name, pm.provider, pm.active, " +
            "       pms.active AS store_active " +
            "FROM payment_methods pm " +
            "LEFT JOIN payment_methods_store pms " +
            "       ON pms.payment_method_id = pm.id AND pms.store_id = ? " +
            "WHERE pm.active = TRUE " +
            "ORDER BY pm.sort_order, pm.id",
            (rs, i) -> {
                boolean globalActive = rs.getBoolean("active");
                Object storeActiveObj = rs.getObject("store_active");
                // null = no override (inherits global), non-null = per-store override
                boolean effectiveActive = storeActiveObj == null ? globalActive : (Boolean) storeActiveObj;
                return new AdminPaymentMethodView(
                    rs.getLong("id"),
                    rs.getString("key"),
                    parseJsonOrNull(rs.getString("display_name")),
                    rs.getString("provider"),
                    effectiveActive,
                    storeActiveObj != null
                );
            },
            storeId
        );
    }

    // Upserts a per-store active override for a payment method.
    // If active=true and no row exists yet, we insert with active=true (same as default, but now explicit).
    // If active=false, we insert/update to disable the method for this store.
    public void setStoreActive(long methodId, long storeId, boolean active) {
        jdbcTemplate.update(
            "INSERT INTO payment_methods_store (payment_method_id, store_id, active) VALUES (?, ?, ?) " +
            "ON DUPLICATE KEY UPDATE active = VALUES(active)",
            methodId, storeId, active
        );
    }

    private JsonNode parseJsonOrNull(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
