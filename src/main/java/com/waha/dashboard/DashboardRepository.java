package com.waha.dashboard;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Repository
public class DashboardRepository {

    private final NamedParameterJdbcTemplate namedJdbc;

    public DashboardRepository(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    // ── KPIs ─────────────────────────────────────────────────────────────────

    public Map<String, Object> getKpis() {
        // Revenue + order counts: today, yesterday, all-time
        Map<String, Object> row = namedJdbc.queryForMap("""
            SELECT
              COALESCE(SUM(CASE WHEN DATE(created_at) = CURDATE()                           THEN total_amount END), 0) AS today_revenue,
              COALESCE(SUM(CASE WHEN DATE(created_at) = DATE_SUB(CURDATE(), INTERVAL 1 DAY) THEN total_amount END), 0) AS yesterday_revenue,
              COALESCE(SUM(total_amount), 0)                                                                           AS total_revenue,
              COUNT(CASE WHEN DATE(created_at) = CURDATE()                           THEN 1 END)                       AS today_orders,
              COUNT(CASE WHEN DATE(created_at) = DATE_SUB(CURDATE(), INTERVAL 1 DAY) THEN 1 END)                       AS yesterday_orders,
              COUNT(*)                                                                                                  AS total_orders,
              COUNT(DISTINCT store_id)                                                                                  AS active_stores
            FROM orders
            WHERE status = 'PAID'
            """, Map.of());

        long kioskCount = namedJdbc.queryForObject(
            "SELECT COUNT(*) FROM users WHERE account_type = 'KIOSK'",
            Map.of(), Long.class
        );

        BigDecimal totalRevenue  = toBD(row.get("total_revenue"));
        BigDecimal todayRevenue  = toBD(row.get("today_revenue"));
        BigDecimal yestRevenue   = toBD(row.get("yesterday_revenue"));
        long totalOrders   = toLong(row.get("total_orders"));
        long todayOrders   = toLong(row.get("today_orders"));
        long yestOrders    = toLong(row.get("yesterday_orders"));
        long activeStores  = toLong(row.get("active_stores"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todayRevenue",       todayRevenue);
        out.put("todayRevenuePct",    pct(todayRevenue, yestRevenue));
        out.put("totalRevenue",       totalRevenue);
        out.put("todayOrders",        todayOrders);
        out.put("todayOrdersPct",     pct(BigDecimal.valueOf(todayOrders), BigDecimal.valueOf(yestOrders)));
        out.put("totalOrders",        totalOrders);
        out.put("totalKiosks",        kioskCount);
        out.put("activeStores",       activeStores);
        out.put("avgRevenuePerKiosk", kioskCount > 0 ? totalRevenue.divide(BigDecimal.valueOf(kioskCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO);
        out.put("avgOrdersPerKiosk",  kioskCount > 0 ? (double) totalOrders / kioskCount : 0.0);
        return out;
    }

    // ── Daily series ──────────────────────────────────────────────────────────

    public List<Map<String, Object>> getSeries(String metric, String period) {
        int days = switch (period) {
            case "7d"  -> 7;
            case "15d" -> 15;
            case "3m"  -> 90;
            default    -> 30; // 1m
        };

        String valueExpr = "revenue".equals(metric)
            ? "COALESCE(SUM(total_amount), 0)"
            : "COUNT(*)";

        List<Map<String, Object>> rows = namedJdbc.queryForList("""
            SELECT DATE(created_at) AS day, %s AS value
            FROM orders
            WHERE status = 'PAID'
              AND created_at >= DATE_SUB(CURDATE(), INTERVAL :days DAY)
            GROUP BY DATE(created_at)
            ORDER BY day ASC
            """.formatted(valueExpr),
            Map.of("days", days)
        );

        // Build a full date range so gaps appear as zero (not missing)
        Map<String, Object> byDay = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            byDay.put(r.get("day").toString(), r.get("value"));
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);
        List<Map<String, Object>> result = new ArrayList<>(days);
        LocalDate start = LocalDate.now().minusDays(days - 1L);
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            String key = d.toString(); // yyyy-MM-dd
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", d.format(fmt));
            point.put("value", byDay.getOrDefault(key, "revenue".equals(metric) ? BigDecimal.ZERO : 0L));
            result.add(point);
        }
        return result;
    }

    // ── Monthly revenue ───────────────────────────────────────────────────────

    public List<Map<String, Object>> getMonthly(String period) {
        int months = switch (period) {
            case "1y" -> 12;
            case "2y" -> 24;
            default   -> 6;  // 6m
        };

        List<Map<String, Object>> rows = namedJdbc.queryForList("""
            SELECT
              DATE_FORMAT(created_at, '%Y-%m')           AS month_key,
              MIN(DATE_FORMAT(created_at, '%b %Y'))      AS label,
              COALESCE(SUM(total_amount), 0)             AS value
            FROM orders
            WHERE status = 'PAID'
              AND created_at >= DATE_SUB(CURDATE(), INTERVAL :months MONTH)
            GROUP BY DATE_FORMAT(created_at, '%Y-%m')
            ORDER BY month_key ASC
            """,
            Map.of("months", months)
        );

        // Fill gaps for months with no orders
        Map<String, Object> byMonth = new LinkedHashMap<>();
        Map<String, String> labelByMonth = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            String k = r.get("month_key").toString();
            byMonth.put(k, r.get("value"));
            labelByMonth.put(k, r.get("label").toString());
        }

        DateTimeFormatter keyFmt   = DateTimeFormatter.ofPattern("yyyy-MM");
        DateTimeFormatter labelFmt = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
        List<Map<String, Object>> result = new ArrayList<>(months);
        LocalDate start = LocalDate.now().withDayOfMonth(1).minusMonths(months - 1L);
        for (int i = 0; i < months; i++) {
            LocalDate d = start.plusMonths(i);
            String key = d.format(keyFmt);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("label", labelByMonth.getOrDefault(key, d.format(labelFmt)));
            point.put("value", byMonth.getOrDefault(key, BigDecimal.ZERO));
            result.add(point);
        }
        return result;
    }

    // ── Recent orders ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> getRecentOrders() {
        return namedJdbc.queryForList(
            "SELECT o.id, o.total_amount AS total, o.status, o.currency, o.created_at," +
            " s.name AS store_name, s.display_name AS store_display_name" +
            " FROM orders o" +
            " JOIN stores s ON o.store_id = s.id" +
            " WHERE o.status = 'PAID'" +
            " ORDER BY o.created_at DESC" +
            " LIMIT 20",
            Map.of());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static BigDecimal toBD(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        return new BigDecimal(v.toString());
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }

    private static Double pct(BigDecimal current, BigDecimal previous) {
        if (previous.compareTo(BigDecimal.ZERO) == 0) return null;
        return current.subtract(previous)
            .divide(previous, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(1, RoundingMode.HALF_UP)
            .doubleValue();
    }
}
