package com.waha.inventory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class InventoryRepository {

    public record VisitRow(
        long id, long organizationId, long employeeId, long storeId,
        String storeName, String storeDisplayName, String status,
        LocalDateTime createdAt, LocalDateTime completedAt
    ) {}

    public record OperationRow(
        long id, long visitId, String operationType, long targetStoreId,
        String targetStoreName, String targetStoreDisplayName, String notes, LocalDateTime createdAt
    ) {}

    public record OperationItemRow(long id, long operationId, long productId, String productName, int quantity) {}

    public record BranchView(long id, String name, String displayName) {}

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── Visits ────────────────────────────────────────────────────────────────

    public long createVisit(long orgId, long employeeId, long storeId) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO inventory_visits (organization_id, employee_id, store_id) VALUES (:org, :emp, :store)",
            new MapSqlParameterSource().addValue("org", orgId).addValue("emp", employeeId).addValue("store", storeId),
            kh
        );
        return kh.getKey().longValue();
    }

    public Optional<VisitRow> findVisitById(long id) {
        List<VisitRow> rows = jdbc.query(
            "SELECT v.id, v.organization_id, v.employee_id, v.store_id, " +
            "s.name AS store_name, s.display_name AS store_display_name, " +
            "v.status, v.created_at, v.completed_at " +
            "FROM inventory_visits v JOIN stores s ON s.id = v.store_id WHERE v.id = :id",
            Map.of("id", id),
            (rs, i) -> new VisitRow(
                rs.getLong("id"), rs.getLong("organization_id"), rs.getLong("employee_id"),
                rs.getLong("store_id"), rs.getString("store_name"), rs.getString("store_display_name"),
                rs.getString("status"),
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("completed_at", LocalDateTime.class)
            )
        );
        return rows.stream().findFirst();
    }

    public void cancelVisit(long visitId) {
        jdbc.update(
            "UPDATE inventory_visits SET status = 'CANCELLED' WHERE id = :id AND status = 'ACTIVE'",
            Map.of("id", visitId)
        );
    }

    // ── Operations ────────────────────────────────────────────────────────────

    public long addOperation(long visitId, String operationType, long targetStoreId, String notes) {
        var kh = new GeneratedKeyHolder();
        jdbc.update(
            "INSERT INTO inventory_operations (visit_id, operation_type, target_store_id, notes) " +
            "VALUES (:vid, :type, :tstore, :notes)",
            new MapSqlParameterSource()
                .addValue("vid",    visitId)
                .addValue("type",   operationType)
                .addValue("tstore", targetStoreId)
                .addValue("notes",  notes),
            kh
        );
        return kh.getKey().longValue();
    }

    public Optional<OperationRow> findOperationById(long id) {
        List<OperationRow> rows = jdbc.query(
            "SELECT o.id, o.visit_id, o.operation_type, o.target_store_id, " +
            "s.name AS target_store_name, s.display_name AS target_store_display_name, " +
            "o.notes, o.created_at " +
            "FROM inventory_operations o JOIN stores s ON s.id = o.target_store_id WHERE o.id = :id",
            Map.of("id", id),
            (rs, i) -> new OperationRow(
                rs.getLong("id"), rs.getLong("visit_id"), rs.getString("operation_type"),
                rs.getLong("target_store_id"), rs.getString("target_store_name"),
                rs.getString("target_store_display_name"),
                rs.getString("notes"), rs.getObject("created_at", LocalDateTime.class)
            )
        );
        return rows.stream().findFirst();
    }

    public List<OperationRow> listOperations(long visitId) {
        return jdbc.query(
            "SELECT o.id, o.visit_id, o.operation_type, o.target_store_id, " +
            "s.name AS target_store_name, s.display_name AS target_store_display_name, " +
            "o.notes, o.created_at " +
            "FROM inventory_operations o JOIN stores s ON s.id = o.target_store_id " +
            "WHERE o.visit_id = :vid ORDER BY o.created_at",
            Map.of("vid", visitId),
            (rs, i) -> new OperationRow(
                rs.getLong("id"), rs.getLong("visit_id"), rs.getString("operation_type"),
                rs.getLong("target_store_id"), rs.getString("target_store_name"),
                rs.getString("target_store_display_name"),
                rs.getString("notes"), rs.getObject("created_at", LocalDateTime.class)
            )
        );
    }

    public void deleteOperation(long operationId) {
        // items cascade-delete via FK ON DELETE CASCADE
        jdbc.update("DELETE FROM inventory_operations WHERE id = :id", Map.of("id", operationId));
    }

    // ── Items ─────────────────────────────────────────────────────────────────

    public void addItem(long operationId, long productId, int quantity) {
        jdbc.update(
            "INSERT INTO inventory_operation_items (operation_id, product_id, quantity) VALUES (:op, :pid, :qty) " +
            "ON DUPLICATE KEY UPDATE quantity = :qty",
            Map.of("op", operationId, "pid", productId, "qty", quantity)
        );
    }

    public List<OperationItemRow> listItems(long operationId) {
        return jdbc.query(
            "SELECT i.id, i.operation_id, i.product_id, p.name AS product_name, i.quantity " +
            "FROM inventory_operation_items i JOIN products p ON p.id = i.product_id " +
            "WHERE i.operation_id = :op ORDER BY p.name",
            Map.of("op", operationId),
            (rs, i) -> {
                String nameJson = rs.getString("product_name");
                // product name is a JSON column — return raw JSON, controller decides how to present it
                return new OperationItemRow(
                    rs.getLong("id"), rs.getLong("operation_id"),
                    rs.getLong("product_id"), nameJson, rs.getInt("quantity")
                );
            }
        );
    }

    // ── Branches ──────────────────────────────────────────────────────────────

    public List<BranchView> listOrgBranches(long organizationId) {
        return jdbc.query(
            "SELECT id, name, display_name FROM stores WHERE organization_id = :org AND active = TRUE ORDER BY name",
            Map.of("org", organizationId),
            (rs, i) -> new BranchView(rs.getLong("id"), rs.getString("name"), rs.getString("display_name"))
        );
    }

    // ── Complete visit (transactional) ────────────────────────────────────────

    @Transactional
    public LocalDateTime completeVisit(long visitId) {
        List<OperationRow> ops = listOperations(visitId);
        for (OperationRow op : ops) {
            List<OperationItemRow> items = listItems(op.id());
            for (OperationItemRow item : items) {
                int qty = item.quantity();
                if ("TRANSFER".equals(op.operationType())) {
                    adjustInventory(op.targetStoreId(), item.productId(), +qty);
                } else {
                    adjustInventory(op.targetStoreId(), item.productId(), -qty);
                }
            }
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update(
            "UPDATE inventory_visits SET status = 'COMPLETED', completed_at = :now WHERE id = :id",
            Map.of("now", now, "id", visitId)
        );
        return now;
    }

    private void adjustInventory(long storeId, long productId, int delta) {
        jdbc.update(
            "INSERT INTO store_inventory (store_id, product_id, quantity) VALUES (:s, :p, :qty) " +
            "ON DUPLICATE KEY UPDATE quantity = quantity + :qty",
            Map.of("s", storeId, "p", productId, "qty", delta)
        );
    }

    // ── Sales deduction ───────────────────────────────────────────────────────

    @Transactional
    public void deductForOrder(String orderId, long storeId) {
        List<Map<String, Object>> items = jdbc.queryForList(
            "SELECT product_id, quantity FROM order_items WHERE order_id = :oid",
            Map.of("oid", orderId)
        );
        for (Map<String, Object> row : items) {
            long productId = ((Number) row.get("product_id")).longValue();
            int  quantity  = ((Number) row.get("quantity")).intValue();
            adjustInventory(storeId, productId, -quantity);
        }
    }

    // ── Inventory read ────────────────────────────────────────────────────────

    public record StockLevel(long storeId, long productId, int quantity) {}

    public List<StockLevel> listStoreInventory(long storeId) {
        return jdbc.query(
            "SELECT store_id, product_id, quantity FROM store_inventory WHERE store_id = :s ORDER BY product_id",
            Map.of("s", storeId),
            (rs, i) -> new StockLevel(rs.getLong("store_id"), rs.getLong("product_id"), rs.getInt("quantity"))
        );
    }

    public Optional<Integer> getStock(long storeId, long productId) {
        List<Integer> r = jdbc.query(
            "SELECT quantity FROM store_inventory WHERE store_id = :s AND product_id = :p",
            Map.of("s", storeId, "p", productId),
            (rs, i) -> rs.getInt("quantity")
        );
        return r.stream().findFirst();
    }
}
