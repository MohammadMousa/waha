package com.waha.reports;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ReportsRepository {

    private final NamedParameterJdbcTemplate namedJdbc;

    public ReportsRepository(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    // ── Lookup helpers for filter dropdowns ───────────────────────────────────

    public List<Map<String, Object>> getAllStores() {
        return namedJdbc.queryForList(
            "SELECT id, name FROM stores WHERE store_type = 'CHILD' AND active = TRUE ORDER BY name",
            Map.of()
        );
    }

    public List<Map<String, Object>> getAllCategories() {
        return namedJdbc.queryForList("""
            SELECT id,
                   COALESCE(JSON_UNQUOTE(JSON_EXTRACT(name, '$.en')),
                            JSON_UNQUOTE(JSON_EXTRACT(name, '$.ar')),
                            'Unnamed') AS name_en
            FROM categories
            WHERE active = TRUE
            ORDER BY sort_order, id
            """, Map.of());
    }

    // ── Products Sales ────────────────────────────────────────────────────────

    public Map<String, Object> getProductsSalesSummary(Filters f) {
        String where = buildWhere(f);
        String sql = """
            SELECT
              COUNT(DISTINCT oi.product_id)      AS total_products,
              COUNT(DISTINCT o.store_id)          AS total_branches,
              COALESCE(SUM(oi.quantity), 0)       AS total_qty_sold,
              COALESCE(SUM(oi.quantity * oi.unit_price), 0) AS total_sales
            FROM order_items oi
            JOIN orders o ON oi.order_id = o.id
            WHERE o.status = 'PAID'
            """ + where;

        return namedJdbc.queryForMap(sql, f.params());
    }

    public long getProductsSalesCount(Filters f) {
        String where = buildWhere(f);
        String sql = """
            SELECT COUNT(*) FROM (
              SELECT oi.product_id, o.store_id, oi.unit_price
              FROM order_items oi
              JOIN orders o ON oi.order_id = o.id
              WHERE o.status = 'PAID'
            """ + where + """

              GROUP BY oi.product_id, o.store_id, oi.unit_price
            ) AS sub
            """;
        Long count = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return count == null ? 0L : count;
    }

    public List<Map<String, Object>> getProductsSalesItems(Filters f, int page, int size) {
        String where = buildWhere(f);
        MapSqlParameterSource params = f.params()
            .addValue("limit", size)
            .addValue("offset", (long) page * size);

        String sql = """
            SELECT
              p.id                                                              AS product_id,
              p.name                                                            AS product_name,
              p.sku,
              p.barcode,
              COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.name, '$.en')),
                       JSON_UNQUOTE(JSON_EXTRACT(c.name, '$.ar')))             AS category_name,
              s.id                                                              AS branch_id,
              s.name                                                            AS branch_name,
              s.display_name                                                    AS branch_display_name,
              SUM(oi.quantity)                                                  AS qty_sold,
              oi.unit_price,
              SUM(oi.quantity * oi.unit_price)                                 AS total
            FROM order_items oi
            JOIN orders o   ON oi.order_id    = o.id
            JOIN products p ON oi.product_id  = p.id
            JOIN stores   s ON o.store_id     = s.id
            LEFT JOIN categories c ON p.category_id = c.id
            WHERE o.status = 'PAID'
            """ + where + """

            GROUP BY p.id, s.id, oi.unit_price
            ORDER BY total DESC
            LIMIT :limit OFFSET :offset
            """;

        return namedJdbc.queryForList(sql, params);
    }

    // ── Orders report ─────────────────────────────────────────────────────────

    public Map<String, Object> getOrdersSummary(OrderFilters f) {
        String sql = """
            SELECT
              COUNT(*)                                   AS total_orders,
              COALESCE(SUM(o.subtotal_amount), 0)        AS sub_total,
              COALESCE(SUM(o.tax_amount), 0)             AS vat,
              COALESCE(SUM(o.total_amount), 0)           AS total
            FROM orders o
            WHERE 1=1
            """ + buildOrderWhere(f);
        return namedJdbc.queryForMap(sql, f.params());
    }

    public long getOrdersCount(OrderFilters f) {
        String sql = "SELECT COUNT(*) FROM orders o WHERE 1=1" + buildOrderWhere(f);
        Long c = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> getOrdersItems(OrderFilters f, int page, int size) {
        MapSqlParameterSource params = f.params()
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = """
            SELECT
              o.display_id,
              s.name                                          AS branch_name,
              s.display_name                                  AS branch_display_name,
              o.username                                      AS kiosk,
              o.payment_reference                             AS ref,
              o.created_at,
              o.subtotal_amount,
              o.tax_amount,
              o.total_amount,
              o.currency,
              o.status,
              COALESCE(
                (SELECT pm.provider FROM payments pm
                 WHERE pm.order_id = o.id AND pm.outcome = 'PAID' LIMIT 1),
                (SELECT pa.provider FROM payment_attempts pa
                 WHERE pa.order_id = o.id LIMIT 1)
              )                                               AS payment_type,
              EXISTS (
                SELECT 1 FROM sync_queue sq
                WHERE sq.entity_id = o.id AND sq.entity_type = 'ORDER'
                  AND sq.status IN ('SYNCED','DONE','COMPLETED')
              )                                               AS synced
            FROM orders o
            JOIN stores s ON o.store_id = s.id
            WHERE 1=1
            """ + buildOrderWhere(f) + """

            ORDER BY o.created_at DESC
            LIMIT :limit OFFSET :offset
            """;
        return namedJdbc.queryForList(sql, params);
    }

    public List<Map<String, Object>> getKiosks() {
        return namedJdbc.queryForList(
            "SELECT username FROM users WHERE account_type = 'KIOSK' AND enabled = TRUE ORDER BY username",
            Map.of()
        );
    }

    public List<Map<String, Object>> getPaymentProviders() {
        return namedJdbc.queryForList(
            "SELECT DISTINCT provider FROM payments WHERE provider IS NOT NULL ORDER BY provider",
            Map.of()
        );
    }

    private String buildOrderWhere(OrderFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.storeId()     != null) sb.append("\n  AND o.store_id   = :storeId");
        if (f.status()      != null) sb.append("\n  AND o.status     = :status");
        if (f.from()        != null) sb.append("\n  AND o.created_at >= :from");
        if (f.to()          != null) sb.append("\n  AND o.created_at <  :to");
        if (f.kiosk()       != null) sb.append("\n  AND o.username   = :kiosk");
        if (f.paymentType() != null) sb.append("""
            \n  AND EXISTS (
              SELECT 1 FROM payments pm
              WHERE pm.order_id = o.id AND pm.provider = :paymentType
            )""");
        if (Boolean.TRUE.equals(f.synced())) sb.append("""
            \n  AND EXISTS (
              SELECT 1 FROM sync_queue sq
              WHERE sq.entity_id = o.id AND sq.entity_type = 'ORDER'
                AND sq.status IN ('SYNCED','DONE','COMPLETED')
            )""");
        if (Boolean.FALSE.equals(f.synced())) sb.append("""
            \n  AND NOT EXISTS (
              SELECT 1 FROM sync_queue sq
              WHERE sq.entity_id = o.id AND sq.entity_type = 'ORDER'
                AND sq.status IN ('SYNCED','DONE','COMPLETED')
            )""");
        return sb.toString();
    }

    record OrderFilters(Long storeId, String status, String from, String to,
                        String kiosk, String paymentType, Boolean synced) {
        MapSqlParameterSource params() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("storeId",     storeId);
            p.addValue("status",      status);
            p.addValue("from",        from);
            p.addValue("to",          to != null ? to + " 23:59:59" : null);
            p.addValue("kiosk",       kiosk);
            p.addValue("paymentType", paymentType);
            return p;
        }
    }

    // ── WHERE builder ─────────────────────────────────────────────────────────

    private String buildWhere(Filters f) {
        StringBuilder sb = new StringBuilder();
        if (f.storeId()    != null) sb.append("\n  AND o.store_id        = :storeId");
        if (f.categoryId() != null) sb.append("\n  AND p.category_id     = :categoryId");
        if (f.productId()  != null) sb.append("\n  AND oi.product_id     = :productId");
        if (f.from()       != null) sb.append("\n  AND o.created_at     >= :from");
        if (f.to()         != null) sb.append("\n  AND o.created_at      < :to");
        return sb.toString();
    }

    // ── Filters record ────────────────────────────────────────────────────────

    record Filters(Long storeId, Long categoryId, Long productId, String from, String to) {
        MapSqlParameterSource params() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("storeId",    storeId);
            p.addValue("categoryId", categoryId);
            p.addValue("productId",  productId);
            p.addValue("from",       from);
            // shift "to" to start of next day so the full end-date is included
            p.addValue("to", to != null ? to + " 23:59:59" : null);
            return p;
        }
    }
}
