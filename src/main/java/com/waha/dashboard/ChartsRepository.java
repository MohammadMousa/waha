package com.waha.dashboard;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class ChartsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ChartsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> revenueByHours(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT HOUR(created_at) AS label, COALESCE(SUM(total_amount), 0) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY HOUR(created_at) ORDER BY HOUR(created_at)",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> revenueByDays(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS label, COALESCE(SUM(total_amount), 0) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d') ORDER BY DATE_FORMAT(created_at, '%Y-%m-%d')",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> revenueByMonths(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT DATE_FORMAT(created_at, '%Y-%m') AS label, COALESCE(SUM(total_amount), 0) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY DATE_FORMAT(created_at, '%Y-%m') ORDER BY DATE_FORMAT(created_at, '%Y-%m')",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> ordersByDays(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS label, COUNT(*) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY DATE_FORMAT(created_at, '%Y-%m-%d') ORDER BY DATE_FORMAT(created_at, '%Y-%m-%d')",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> ordersByHours(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT HOUR(created_at) AS label, COUNT(*) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY HOUR(created_at) ORDER BY HOUR(created_at)",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> ordersByMonths(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT DATE_FORMAT(created_at, '%Y-%m') AS label, COUNT(*) AS value" +
            " FROM orders WHERE status = 'PAID' AND created_at BETWEEN :from AND :to" +
            " GROUP BY DATE_FORMAT(created_at, '%Y-%m') ORDER BY DATE_FORMAT(created_at, '%Y-%m')",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> revenueByProducts(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT MIN(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(p.name, '$.en'))," +
            " JSON_UNQUOTE(JSON_EXTRACT(p.name, '$.ar')), 'Unknown')) AS label," +
            " COALESCE(SUM(oi.unit_price * oi.quantity), 0) AS value" +
            " FROM order_items oi" +
            " JOIN orders o ON oi.order_id = o.id" +
            " JOIN products p ON oi.product_id = p.id" +
            " WHERE o.status = 'PAID' AND o.created_at BETWEEN :from AND :to" +
            " GROUP BY oi.product_id ORDER BY value DESC LIMIT 10",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> revenueByCategories(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT MIN(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(c.name, '$.en'))," +
            " JSON_UNQUOTE(JSON_EXTRACT(c.name, '$.ar')), 'Uncategorized')) AS label," +
            " COALESCE(SUM(oi.unit_price * oi.quantity), 0) AS value" +
            " FROM order_items oi" +
            " JOIN orders o ON oi.order_id = o.id" +
            " JOIN products p ON oi.product_id = p.id" +
            " LEFT JOIN categories c ON p.category_id = c.id" +
            " WHERE o.status = 'PAID' AND o.created_at BETWEEN :from AND :to" +
            " GROUP BY p.category_id ORDER BY value DESC LIMIT 10",
            Map.of("from", from, "to", to));
    }

    public List<Map<String, Object>> revenueByBranches(LocalDateTime from, LocalDateTime to) {
        return jdbc.queryForList(
            "SELECT MIN(COALESCE(JSON_UNQUOTE(JSON_EXTRACT(s.display_name, '$.en')), s.name, 'Unknown')) AS label," +
            " COALESCE(SUM(o.total_amount), 0) AS value" +
            " FROM orders o" +
            " JOIN stores s ON o.store_id = s.id" +
            " WHERE o.status = 'PAID' AND o.created_at BETWEEN :from AND :to" +
            " GROUP BY o.store_id ORDER BY value DESC",
            Map.of("from", from, "to", to));
    }
}
