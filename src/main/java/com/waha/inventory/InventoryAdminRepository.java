package com.waha.inventory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class InventoryAdminRepository {

    private final NamedParameterJdbcTemplate namedJdbc;

    public InventoryAdminRepository(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    // ── Visits ────────────────────────────────────────────────────────────────

    record VisitFilters(long orgId, Long branchId, Long employeeId, String from, String to) {
        MapSqlParameterSource params() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("orgId",      orgId);
            p.addValue("branchId",   branchId);
            p.addValue("employeeId", employeeId);
            p.addValue("from",       from);
            p.addValue("to",         to != null ? to + " 23:59:59" : null);
            return p;
        }
    }

    private String buildVisitWhere(VisitFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.branchId()   != null) sb.append("\n  AND v.store_id    = :branchId");
        if (f.employeeId() != null) sb.append("\n  AND v.employee_id = :employeeId");
        if (f.from()       != null) sb.append("\n  AND v.created_at >= :from");
        if (f.to()         != null) sb.append("\n  AND v.created_at <= :to");
        return sb.toString();
    }

    public long countVisits(VisitFilters f) {
        String sql = "SELECT COUNT(*) FROM inventory_visits v WHERE v.organization_id = :orgId" + buildVisitWhere(f);
        Long c = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> listVisits(VisitFilters f, int page, int size) {
        MapSqlParameterSource p = f.params()
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = """
            SELECT
              v.id                                                               AS visit_id,
              v.employee_id,
              CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')) AS employee_name,
              v.store_id                                                         AS branch_id,
              s.name                                                             AS branch_name,
              s.display_name                                                     AS branch_display_name,
              v.status,
              v.created_at                                                       AS start_time,
              v.completed_at                                                     AS end_time
            FROM inventory_visits v
            JOIN employees e ON e.id = v.employee_id
            JOIN stores    s ON s.id = v.store_id
            WHERE v.organization_id = :orgId
            """ + buildVisitWhere(f) + """

            ORDER BY v.created_at DESC
            LIMIT :limit OFFSET :offset
            """;
        return namedJdbc.queryForList(sql, p);
    }

    // ── Movements (Transfers / Returns) ───────────────────────────────────────

    record MovementFilters(long orgId, String operationType, Long branchId, Long productId,
                           Long employeeId, String from, String to) {
        MapSqlParameterSource params() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("orgId",      orgId);
            p.addValue("opType",     operationType);
            p.addValue("branchId",   branchId);
            p.addValue("productId",  productId);
            p.addValue("employeeId", employeeId);
            p.addValue("from",       from);
            p.addValue("to",         to != null ? to + " 23:59:59" : null);
            return p;
        }
    }

    private String buildMovementWhere(MovementFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.branchId()   != null) sb.append("\n  AND op.target_store_id = :branchId");
        if (f.productId()  != null) sb.append("\n  AND oi.product_id      = :productId");
        if (f.employeeId() != null) sb.append("\n  AND v.employee_id      = :employeeId");
        if (f.from()       != null) sb.append("\n  AND op.created_at     >= :from");
        if (f.to()         != null) sb.append("\n  AND op.created_at     <= :to");
        return sb.toString();
    }

    public long countMovements(MovementFilters f) {
        String sql = """
            SELECT COUNT(*)
            FROM inventory_operation_items oi
            JOIN inventory_operations op ON op.id = oi.operation_id
            JOIN inventory_visits     v  ON v.id  = op.visit_id
            WHERE v.organization_id = :orgId AND op.operation_type = :opType
            """ + buildMovementWhere(f);
        Long c = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> listMovements(MovementFilters f, int page, int size) {
        MapSqlParameterSource p = f.params()
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = """
            SELECT
              op.created_at                                                      AS date,
              oi.product_id,
              p.name                                                             AS product_name,
              op.target_store_id                                                 AS branch_id,
              s.name                                                             AS branch_name,
              s.display_name                                                     AS branch_display_name,
              oi.quantity,
              v.employee_id,
              CONCAT(COALESCE(e.first_name,''), ' ', COALESCE(e.last_name,'')) AS employee_name,
              v.id                                                               AS visit_id
            FROM inventory_operation_items oi
            JOIN inventory_operations op ON op.id = oi.operation_id
            JOIN inventory_visits     v  ON v.id  = op.visit_id
            JOIN products             p  ON p.id  = oi.product_id
            JOIN stores               s  ON s.id  = op.target_store_id
            JOIN employees            e  ON e.id  = v.employee_id
            WHERE v.organization_id = :orgId AND op.operation_type = :opType
            """ + buildMovementWhere(f) + """

            ORDER BY op.created_at DESC
            LIMIT :limit OFFSET :offset
            """;
        return namedJdbc.queryForList(sql, p);
    }

    // ── Stock: BRANCHES scope ─────────────────────────────────────────────────

    record StockFilters(long orgId, Long branchId, Long productId, Long categoryId) {
        MapSqlParameterSource params() {
            MapSqlParameterSource p = new MapSqlParameterSource();
            p.addValue("orgId",      orgId);
            p.addValue("branchId",   branchId);
            p.addValue("productId",  productId);
            p.addValue("categoryId", categoryId);
            return p;
        }
    }

    private String buildStockBranchesWhere(StockFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.branchId()   != null) sb.append("\n  AND si.store_id   = :branchId");
        if (f.productId()  != null) sb.append("\n  AND si.product_id = :productId");
        if (f.categoryId() != null) sb.append("\n  AND p.category_id = :categoryId");
        return sb.toString();
    }

    public long countStockBranches(StockFilters f) {
        String sql = """
            SELECT COUNT(*)
            FROM store_inventory si
            JOIN stores   s ON s.id = si.store_id
            JOIN products p ON p.id = si.product_id
            WHERE s.organization_id = :orgId
            """ + buildStockBranchesWhere(f);
        Long c = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> listStockBranches(StockFilters f, int page, int size) {
        MapSqlParameterSource p = f.params()
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = """
            SELECT
              si.store_id      AS branch_id,
              s.name           AS branch_name,
              s.display_name   AS branch_display_name,
              si.product_id,
              p.name           AS product_name,
              si.quantity
            FROM store_inventory si
            JOIN stores   s ON s.id = si.store_id
            JOIN products p ON p.id = si.product_id
            WHERE s.organization_id = :orgId
            """ + buildStockBranchesWhere(f) + """

            ORDER BY s.name, p.name
            LIMIT :limit OFFSET :offset
            """;
        return namedJdbc.queryForList(sql, p);
    }

    // ── Stock: COMPANY scope ──────────────────────────────────────────────────

    private String buildStockCompanyWhere(StockFilters f) {
        StringBuilder sb = new StringBuilder();
        if (f.productId()  != null) sb.append("\n  AND si.product_id = :productId");
        if (f.categoryId() != null) sb.append("\n  AND p.category_id = :categoryId");
        return sb.toString();
    }

    public long countStockCompany(StockFilters f) {
        String sql = """
            SELECT COUNT(DISTINCT si.product_id)
            FROM store_inventory si
            JOIN stores   s ON s.id = si.store_id
            JOIN products p ON p.id = si.product_id
            WHERE s.organization_id = :orgId
            """ + buildStockCompanyWhere(f);
        Long c = namedJdbc.queryForObject(sql, f.params(), Long.class);
        return c == null ? 0L : c;
    }

    public List<Map<String, Object>> listStockCompany(StockFilters f, int page, int size) {
        MapSqlParameterSource p = f.params()
            .addValue("limit",  size)
            .addValue("offset", (long) page * size);
        String sql = """
            SELECT
              si.product_id,
              p.name           AS product_name,
              SUM(si.quantity) AS total_quantity
            FROM store_inventory si
            JOIN stores   s ON s.id = si.store_id
            JOIN products p ON p.id = si.product_id
            WHERE s.organization_id = :orgId
            """ + buildStockCompanyWhere(f) + """

            GROUP BY si.product_id, p.name
            ORDER BY p.name
            LIMIT :limit OFFSET :offset
            """;
        return namedJdbc.queryForList(sql, p);
    }
}
