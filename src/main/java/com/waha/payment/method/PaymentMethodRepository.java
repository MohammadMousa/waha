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
    // the global sort_order. This lets a branch re-order methods to match
    // its own flow without touching the global config.
    public List<PaymentMethodSummary> findForStore(long storeId, String mode) {
        return jdbcTemplate.query(
            "SELECT pm.id, pm.key, pm.display_name, pm.provider, pm.offline_capable, " +
            "       COALESCE(pms.sort_order, pm.sort_order) AS effective_sort_order " +
            "FROM payment_methods pm " +
            "LEFT JOIN payment_methods_store pms " +
            "       ON pms.payment_method_id = pm.id AND pms.store_id = ? " +
            "WHERE pm.active = TRUE " +
            "  AND FIND_IN_SET(?, pm.available_modes) > 0 " +
            "  AND (pms.id IS NULL OR pms.active = TRUE) " +
            "ORDER BY effective_sort_order, pm.id",
            (rs, i) -> {
                JsonNode displayName = parseJsonOrNull(rs.getString("display_name"));
                return new PaymentMethodSummary(
                    rs.getLong("id"),
                    rs.getString("key"),
                    displayName,
                    rs.getString("provider"),
                    rs.getBoolean("offline_capable"),
                    rs.getInt("effective_sort_order")
                );
            },
            storeId, mode
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
