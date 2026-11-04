package com.waha.coupon;

import com.waha.offer.OfferClock;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

// Coupon sales report: paid orders that used a coupon, as a list of orders or grouped by employee or coupon.
// Amounts are the stored order amounts (sub total after discounts, VAT, total), like invoices.
@Service
public class CouponReportService {

    public static final class InvalidFilter extends RuntimeException {
        public final Map<String, String> errors;
        InvalidFilter(Map<String, String> errors) { super("Invalid filter"); this.errors = errors; }
    }

    public record Filter(String groupBy, String from, String to, Long storeId, Long couponId, Long employeeId,
                         Long offerId, String username, String sort, String dir) {}

    public record Page(Map<String, Object> summary, List<Map<String, Object>> items, long total) {}

    private static final int EXPORT_LIMIT = 50_000;

    // When the order was paid: the paid payment's time, else the last update of the order.
    private static final String PAID_AT =
        "COALESCE((SELECT MAX(p.created_at) FROM payments p WHERE p.order_id = o.id AND p.outcome = 'PAID'), o.updated_at)";

    private static final String FROM = """
        FROM coupon_redemptions r
        JOIN orders o ON o.id = r.order_id AND o.status = 'PAID'
        JOIN coupons c ON c.id = r.coupon_id
        JOIN stores s ON s.id = o.store_id
        LEFT JOIN employees e ON e.id = r.employee_id
        """;

    private static final String EMPLOYEE_NAME =
        "COALESCE(NULLIF(TRIM(CONCAT_WS(' ', e.first_name, e.last_name)), ''), e.username)";

    private final NamedParameterJdbcTemplate jdbc;
    private final OfferClock clock;

    public CouponReportService(NamedParameterJdbcTemplate jdbc, OfferClock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public Page report(long org, Filter f, int page, int size) {
        return run(org, f, page, size, false);
    }

    private Page run(long org, Filter f, int page, int size, boolean all) {
        Map<String, String> errors = new LinkedHashMap<>();
        String group = f.groupBy() == null || f.groupBy().isBlank() ? "orders" : f.groupBy().trim().toLowerCase();
        if (!List.of("orders", "employee", "coupon").contains(group)) errors.put("groupBy", "groupBy must be orders, employee or coupon");
        LocalDate from = date(f.from(), "from", errors);
        LocalDate to = date(f.to(), "to", errors);
        if (from != null && to != null && to.isBefore(from)) errors.put("to", "to must not be before from");
        Map<String, String> sorts = sorts(group);
        String sortKey = f.sort() == null || f.sort().isBlank() ? defaultSort(group) : f.sort();
        if (!sorts.containsKey(sortKey)) errors.put("sort", "sort must be one of " + String.join(", ", sorts.keySet()));
        String dir = f.dir() == null || f.dir().isBlank() ? "desc" : f.dir().toLowerCase();
        if (!dir.equals("asc") && !dir.equals("desc")) errors.put("dir", "dir must be asc or desc");
        if (!errors.isEmpty()) throw new InvalidFilter(errors);

        MapSqlParameterSource p = new MapSqlParameterSource("org", org);
        StringBuilder where = new StringBuilder(" WHERE c.organization_id = :org");
        if (from != null) {
            where.append(" AND ").append(PAID_AT).append(" >= :from");
            p.addValue("from", Timestamp.from(from.atStartOfDay(clock.zone()).toInstant()));
        }
        if (to != null) {
            where.append(" AND ").append(PAID_AT).append(" < :to");
            p.addValue("to", Timestamp.from(to.plusDays(1).atStartOfDay(clock.zone()).toInstant()));
        }
        if (f.storeId() != null)    { where.append(" AND o.store_id = :store");     p.addValue("store", f.storeId()); }
        if (f.couponId() != null)   { where.append(" AND r.coupon_id = :coupon");   p.addValue("coupon", f.couponId()); }
        if (f.employeeId() != null) { where.append(" AND r.employee_id = :emp");    p.addValue("emp", f.employeeId()); }
        if (f.offerId() != null)    { where.append(" AND r.offer_id = :offer");     p.addValue("offer", f.offerId()); }
        if (f.username() != null && !f.username().isBlank()) {
            where.append(" AND o.username = :user");
            p.addValue("user", f.username().trim());
        }

        Map<String, Object> summary = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT o.id) AS orders, COALESCE(SUM(o.subtotal_amount), 0) AS sub_total,
                   COALESCE(SUM(o.tax_amount), 0) AS vat, COALESCE(SUM(o.total_amount), 0) AS total,
                   COALESCE(SUM(r.discount_amount), 0) AS discount
            """ + FROM + where, p, (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("orders", rs.getLong("orders"));
                m.put("subTotal", rs.getBigDecimal("sub_total"));
                m.put("vat", rs.getBigDecimal("vat"));
                m.put("total", rs.getBigDecimal("total"));
                m.put("discount", rs.getBigDecimal("discount"));
                return m;
            });

        String order = " ORDER BY " + sorts.get(sortKey) + " " + dir.toUpperCase() + ", " + tiebreak(group);
        String limit = "";
        if (all) {
            limit = " LIMIT " + EXPORT_LIMIT;
        } else {
            limit = " LIMIT :lim OFFSET :off";
            p.addValue("lim", size);
            p.addValue("off", (long) page * size);
        }

        long total;
        String select;
        switch (group) {
            case "employee" -> {
                total = count(p, where, "SELECT COUNT(*) FROM (SELECT 1 " + FROM + where + " GROUP BY r.employee_id) g");
                select = "SELECT r.employee_id AS employee_id, " + EMPLOYEE_NAME + " AS employee_name, " + AGG + FROM + where
                    + " GROUP BY r.employee_id, " + EMPLOYEE_NAME;
            }
            case "coupon" -> {
                total = count(p, where, "SELECT COUNT(*) FROM (SELECT 1 " + FROM + where + " GROUP BY r.coupon_id) g");
                select = "SELECT r.coupon_id AS coupon_id, c.code AS code, MAX(r.offer_name_snapshot) AS offer_name, "
                    + "c.employee_id AS employee_id, " + EMPLOYEE_NAME + " AS employee_name, " + AGG + FROM + where
                    + " GROUP BY r.coupon_id, c.code, c.employee_id, " + EMPLOYEE_NAME;
            }
            default -> {
                total = ((Number) summary.get("orders")).longValue();
                select = "SELECT o.id AS order_id, o.display_id, o.store_id, s.name AS store_name, o.username, "
                    + PAID_AT + " AS paid_at, o.created_at, r.coupon_id, r.code_snapshot AS code, r.offer_name_snapshot AS offer_name, "
                    + "r.employee_id, " + EMPLOYEE_NAME + " AS employee_name, o.subtotal_amount AS sub_total, o.tax_amount AS vat, "
                    + "o.total_amount AS total, r.discount_amount AS discount, o.discount_amount AS order_discount " + FROM + where;
            }
        }
        List<Map<String, Object>> items = jdbc.query(select + order + limit, p, (rs, i) -> row(group, rs));
        return new Page(summary, items, total);
    }

    private static final String AGG = "COUNT(DISTINCT o.id) AS orders, SUM(o.subtotal_amount) AS sub_total, "
        + "SUM(o.tax_amount) AS vat, SUM(o.total_amount) AS total, SUM(r.discount_amount) AS discount ";

    private long count(MapSqlParameterSource p, CharSequence where, String sql) {
        Long n = jdbc.queryForObject(sql, p, Long.class);
        return n == null ? 0 : n;
    }

    private static Map<String, String> sorts(String group) {
        Map<String, String> m = new LinkedHashMap<>();
        switch (group) {
            case "employee" -> {
                m.put("employeeName", EMPLOYEE_NAME);
                m.put("orders", "orders");
                m.put("subTotal", "sub_total"); m.put("vat", "vat"); m.put("total", "total"); m.put("discount", "discount");
            }
            case "coupon" -> {
                m.put("code", "c.code");
                m.put("employeeName", EMPLOYEE_NAME);
                m.put("orders", "orders");
                m.put("subTotal", "sub_total"); m.put("vat", "vat"); m.put("total", "total"); m.put("discount", "discount");
            }
            default -> {
                m.put("paidAt", PAID_AT);
                m.put("displayId", "o.display_id");
                m.put("username", "o.username");
                m.put("code", "r.code_snapshot");
                m.put("employeeName", EMPLOYEE_NAME);
                m.put("subTotal", "o.subtotal_amount"); m.put("vat", "o.tax_amount"); m.put("total", "o.total_amount");
                m.put("discount", "r.discount_amount");
            }
        }
        return m;
    }

    private static String defaultSort(String group) {
        return group.equals("orders") ? "paidAt" : "total";
    }

    private static String tiebreak(String group) {
        return switch (group) {
            case "employee" -> "r.employee_id";
            case "coupon" -> "r.coupon_id";
            default -> "o.id";
        };
    }

    private static Map<String, Object> row(String group, ResultSet rs) throws SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        switch (group) {
            case "employee" -> {
                m.put("employeeId", nullableLong(rs, "employee_id"));
                m.put("employeeName", rs.getString("employee_name"));
            }
            case "coupon" -> {
                m.put("couponId", rs.getLong("coupon_id"));
                m.put("code", rs.getString("code"));
                m.put("offerName", rs.getString("offer_name"));
                m.put("employeeId", nullableLong(rs, "employee_id"));
                m.put("employeeName", rs.getString("employee_name"));
            }
            default -> {
                m.put("orderId", rs.getString("order_id"));
                m.put("displayId", nullableLong(rs, "display_id"));
                m.put("storeId", rs.getLong("store_id"));
                m.put("storeName", rs.getString("store_name"));
                m.put("username", rs.getString("username"));
                Timestamp paid = rs.getTimestamp("paid_at");
                m.put("paidAt", paid != null ? paid.toInstant().toString() : null);
                Timestamp created = rs.getTimestamp("created_at");
                m.put("createdAt", created != null ? created.toInstant().toString() : null);
                m.put("couponId", rs.getLong("coupon_id"));
                m.put("code", rs.getString("code"));
                m.put("offerName", rs.getString("offer_name"));
                m.put("employeeId", nullableLong(rs, "employee_id"));
                m.put("employeeName", rs.getString("employee_name"));
                m.put("orderDiscount", rs.getBigDecimal("order_discount"));
            }
        }
        if (!group.equals("orders")) m.put("orders", rs.getLong("orders"));
        m.put("subTotal", rs.getBigDecimal("sub_total"));
        m.put("vat", rs.getBigDecimal("vat"));
        m.put("total", rs.getBigDecimal("total"));
        m.put("discount", rs.getBigDecimal("discount"));
        return m;
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate date(String raw, String field, Map<String, String> errors) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            errors.put(field, field + " must be a date like 2026-12-31");
            return null;
        }
    }

    // ── export ──────────────────────────────────────────────────────────────

    public byte[] export(long org, Filter f, String format) {
        Page data = run(org, f, 0, 0, true);
        String group = f.groupBy() == null || f.groupBy().isBlank() ? "orders" : f.groupBy().trim().toLowerCase();
        List<String> keys = switch (group) {
            case "employee" -> List.of("employeeName", "orders", "subTotal", "vat", "total", "discount");
            case "coupon" -> List.of("code", "offerName", "employeeName", "orders", "subTotal", "vat", "total", "discount");
            default -> List.of("displayId", "paidAt", "storeName", "username", "code", "employeeName",
                "subTotal", "vat", "total", "discount");
        };
        List<List<String>> rows = new ArrayList<>();
        for (Map<String, Object> item : data.items()) {
            List<String> r = new ArrayList<>();
            for (String k : keys) r.add(item.get(k) == null ? "" : String.valueOf(item.get(k)));
            rows.add(r);
        }
        return format.equals("xlsx") ? toXlsx(keys, rows) : toCsv(keys, rows);
    }

    private static byte[] toCsv(List<String> header, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿").append(String.join(",", header)).append("\r\n");
        for (List<String> r : rows) {
            StringJoiner j = new StringJoiner(",");
            for (String c : r) j.add(csvCell(c));
            sb.append(j).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    // A cell starting with = + - @ would run as a formula in a spreadsheet, so it gets a leading apostrophe.
    private static String csvCell(String c) {
        if (c == null || c.isEmpty()) return "";
        if ("=+-@".indexOf(c.charAt(0)) >= 0 && !isNumber(c)) c = "'" + c;
        boolean quote = c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r");
        return quote ? "\"" + c.replace("\"", "\"\"") + "\"" : c;
    }

    private static boolean isNumber(String c) {
        try { new BigDecimal(c); return true; } catch (NumberFormatException e) { return false; }
    }

    private static byte[] toXlsx(List<String> header, List<List<String>> rows) {
        Set<String> money = Set.of("subTotal", "vat", "total", "discount");
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Coupon sales");
            Row h = sheet.createRow(0);
            for (int c = 0; c < header.size(); c++) h.createCell(c).setCellValue(header.get(c));
            var style = wb.createCellStyle();
            style.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            for (int i = 0; i < rows.size(); i++) {
                Row r = sheet.createRow(i + 1);
                for (int c = 0; c < header.size(); c++) {
                    Cell cell = r.createCell(c);
                    String v = rows.get(i).get(c);
                    if (money.contains(header.get(c)) && !v.isEmpty()) {
                        cell.setCellValue(new BigDecimal(v).doubleValue());
                        cell.setCellStyle(style);
                    } else {
                        cell.setCellValue(v);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the XLSX file", e);
        }
    }
}
