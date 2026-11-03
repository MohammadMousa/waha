package com.waha.product;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

// Bulk export and import of products as CSV or XLSX. Prices are the stored prices without VAT.
@Service
public class ProductSheetService {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_ROWS = 5000;

    static final List<String> COLUMNS = List.of("barcode", "name_en", "name_ar", "description_en", "description_ar",
        "price", "category", "active", "tags", "additional_barcodes");

    public record RowError(int row, String field, String message) {}

    public record ImportResult(String mode, int created, int updated, int unchanged, List<RowError> errors) {}

    // Thrown for a file that cannot be processed at all (too big, unreadable, no barcode column).
    public static class SheetException extends RuntimeException {
        public final int status;
        public SheetException(int status, String message) { super(message); this.status = status; }
    }

    private record Existing(long id, String barcode, String nameEn, String nameAr, String descEn, String descAr,
                            BigDecimal price, boolean active, Long categoryId,
                            Set<String> tags, List<String> extraBarcodes) {}

    private record Plan(int row, Long id, String barcode, ObjectNode body, List<String> tags,
                        List<String> newBarcodes, boolean create, boolean changed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProductRepository products;
    private final TransactionTemplate tx;

    public ProductSheetService(JdbcTemplate jdbc, ObjectMapper mapper, ProductRepository products,
                               PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.products = products;
        this.tx = new TransactionTemplate(txManager);
    }

    // ── data ────────────────────────────────────────────────────────────────

    private String jsonText(String raw, String lang) {
        if (raw == null) return "";
        try {
            JsonNode n = mapper.readTree(raw);
            JsonNode v = n.get(lang);
            return v == null || v.isNull() ? "" : v.asText();
        } catch (Exception e) {
            return "";
        }
    }

    private Map<Long, JsonNode> categories(long orgId) {
        Map<Long, JsonNode> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name FROM categories WHERE company_id = ?", rs -> {
            try { out.put(rs.getLong(1), mapper.readTree(rs.getString(2))); } catch (Exception ignored) { }
        }, orgId);
        return out;
    }

    private List<Existing> loadProducts(long orgId) {
        Map<Long, Set<String>> tags = new HashMap<>();
        jdbc.query("SELECT t.product_id, t.tag FROM product_tags t JOIN products p ON p.id = t.product_id "
            + "WHERE p.company_id = ?", rs -> {
            tags.computeIfAbsent(rs.getLong(1), k -> new TreeSet<>()).add(rs.getString(2));
        }, orgId);
        Map<Long, List<String>> extras = new HashMap<>();
        jdbc.query("SELECT b.product_id, b.barcode FROM product_barcodes b JOIN products p ON p.id = b.product_id "
            + "WHERE p.company_id = ? AND b.is_primary = 0 ORDER BY b.id", rs -> {
            extras.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>()).add(rs.getString(2));
        }, orgId);
        return jdbc.query("SELECT id, barcode, name, description, price, active, category_id FROM products "
            + "WHERE company_id = ? ORDER BY id", (rs, i) -> {
            long id = rs.getLong("id");
            long cat = rs.getLong("category_id");
            Long categoryId = rs.wasNull() ? null : cat;
            return new Existing(id, rs.getString("barcode"),
                jsonText(rs.getString("name"), "en"), jsonText(rs.getString("name"), "ar"),
                jsonText(rs.getString("description"), "en"), jsonText(rs.getString("description"), "ar"),
                rs.getBigDecimal("price"), rs.getBoolean("active"), categoryId,
                tags.getOrDefault(id, new TreeSet<>()), extras.getOrDefault(id, List.of()));
        }, orgId);
    }

    // ── export ──────────────────────────────────────────────────────────────

    public byte[] export(long orgId, String format, Long categoryId, Boolean active) {
        Map<Long, JsonNode> cats = categories(orgId);
        List<List<String>> rows = new ArrayList<>();
        for (Existing p : loadProducts(orgId)) {
            if (categoryId != null && !categoryId.equals(p.categoryId())) continue;
            if (active != null && active != p.active()) continue;
            String category = "";
            if (p.categoryId() != null && cats.get(p.categoryId()) != null) {
                JsonNode n = cats.get(p.categoryId());
                category = text(n, "en").isEmpty() ? text(n, "ar") : text(n, "en");
            }
            rows.add(List.of(p.barcode(), p.nameEn(), p.nameAr(), p.descEn(), p.descAr(),
                p.price().setScale(2, RoundingMode.HALF_UP).toPlainString(), category, String.valueOf(p.active()),
                String.join(";", p.tags()), String.join(";", p.extraBarcodes())));
        }
        return "xlsx".equals(format) ? toXlsx(rows) : toCsv(rows);
    }

    private static String text(JsonNode n, String lang) {
        JsonNode v = n.get(lang);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static byte[] toCsv(List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(String.join(",", COLUMNS)).append("\r\n");
        for (List<String> r : rows) {
            StringJoiner j = new StringJoiner(",");
            for (String c : r) j.add(csvCell(c));
            sb.append(j).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csvCell(String c) {
        if (c == null) return "";
        boolean quote = c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r");
        return quote ? "\"" + c.replace("\"", "\"\"") + "\"" : c;
    }

    private static byte[] toXlsx(List<List<String>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Products");
            Row header = sheet.createRow(0);
            for (int c = 0; c < COLUMNS.size(); c++) header.createCell(c).setCellValue(COLUMNS.get(c));
            var priceStyle = wb.createCellStyle();
            priceStyle.setDataFormat(wb.createDataFormat().getFormat("0.00"));
            for (int i = 0; i < rows.size(); i++) {
                Row r = sheet.createRow(i + 1);
                List<String> v = rows.get(i);
                for (int c = 0; c < COLUMNS.size(); c++) {
                    Cell cell = r.createCell(c);
                    if (c == 5) {
                        cell.setCellValue(new BigDecimal(v.get(c)).doubleValue());
                        cell.setCellStyle(priceStyle);
                    } else {
                        cell.setCellValue(v.get(c));     // text, so barcodes keep their leading zeros
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the XLSX file", e);
        }
    }

    // ── reading a file ──────────────────────────────────────────────────────

    private record Sheet2(List<String> header, List<Integer> rowNumbers, List<List<String>> rows) {}

    private Sheet2 read(byte[] data, String filename) {
        if (data == null || data.length == 0) throw new SheetException(400, "The file is empty");
        if (data.length > MAX_BYTES) throw new SheetException(413, "The file is larger than 5 MB");
        boolean xlsx = filename != null && filename.toLowerCase().endsWith(".xlsx")
            || (data.length > 3 && data[0] == 'P' && data[1] == 'K');
        List<List<String>> all = xlsx ? readXlsx(data) : readCsv(data);
        if (all.isEmpty()) throw new SheetException(400, "The file has no header row");
        List<String> header = new ArrayList<>();
        for (String h : all.get(0)) header.add(h.replace("﻿", "").trim().toLowerCase());
        if (!header.contains("barcode")) throw new SheetException(400, "The file needs a barcode column");
        List<Integer> nums = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            List<String> r = all.get(i);
            if (r.stream().allMatch(c -> c.trim().isEmpty())) continue;       // blank line
            nums.add(i + 1);
            rows.add(r);
        }
        if (rows.size() > MAX_ROWS) throw new SheetException(413, "The file has more than 5000 rows");
        return new Sheet2(header, nums, rows);
    }

    static List<List<String>> readCsv(byte[] data) {
        String s = new String(data, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else quoted = false;
                } else cell.append(ch);
            } else if (ch == '"') quoted = true;
            else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
            else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') i++;
                row.add(cell.toString()); cell.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else cell.append(ch);
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        return rows;
    }

    private List<List<String>> readXlsx(byte[] data) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
            Sheet sheet = wb.getSheet("Products");
            if (sheet == null) sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<List<String>> rows = new ArrayList<>();
            int last = sheet.getLastRowNum();
            for (int i = 0; i <= last; i++) {
                Row r = sheet.getRow(i);
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < COLUMNS.size() + 5; c++) {
                    Cell cell = r == null ? null : r.getCell(c);
                    cells.add(cell == null ? "" : cellText(cell, fmt));
                }
                rows.add(cells);
                if (rows.size() > MAX_ROWS + 50) break;
            }
            return rows;
        } catch (SheetException e) {
            throw e;
        } catch (Exception e) {
            throw new SheetException(400, "The file could not be read as an XLSX file");
        }
    }

    private static String cellText(Cell cell, DataFormatter fmt) {
        CellType t = cell.getCellType();
        if (t == CellType.NUMERIC && !org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) {
            return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
        }
        if (t == CellType.BOOLEAN) return String.valueOf(cell.getBooleanCellValue());
        return fmt.formatCellValue(cell).trim();
    }

    // ── import ──────────────────────────────────────────────────────────────

    public ImportResult importFile(long orgId, byte[] data, String filename, boolean apply) {
        Sheet2 sheet = read(data, filename);
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < sheet.header().size(); i++) col.putIfAbsent(sheet.header().get(i), i);

        Map<String, Existing> byBarcode = new HashMap<>();
        for (Existing e : loadProducts(orgId)) byBarcode.put(e.barcode(), e);
        Map<String, List<Long>> categoryByName = new HashMap<>();
        categories(orgId).forEach((id, n) -> {
            for (String lang : List.of("en", "ar")) {
                String name = text(n, lang).trim().toLowerCase();
                if (!name.isEmpty()) categoryByName.computeIfAbsent(name, k -> new ArrayList<>()).add(id);
            }
        });

        List<RowError> errors = new ArrayList<>();
        List<Plan> plans = new ArrayList<>();
        Map<String, Integer> claimed = new HashMap<>();   // every barcode used so far in this file -> row
        int unchanged = 0;

        for (int i = 0; i < sheet.rows().size(); i++) {
            int rowNo = sheet.rowNumbers().get(i);
            List<String> cells = sheet.rows().get(i);
            Function3 get = name -> {
                Integer idx = col.get(name);
                return idx == null || idx >= cells.size() ? "" : cells.get(idx).trim();
            };
            int before = errors.size();

            String barcode = get.apply("barcode");
            if (barcode.isEmpty()) { errors.add(new RowError(rowNo, "barcode", "barcode is required")); continue; }
            if (barcode.length() > 64) { errors.add(new RowError(rowNo, "barcode", "barcode is longer than 64 characters")); continue; }
            if (claimed.containsKey(barcode)) {
                errors.add(new RowError(rowNo, "barcode", "barcode already appears in row " + claimed.get(barcode)));
                continue;
            }
            claimed.put(barcode, rowNo);
            Existing ex = byBarcode.get(barcode);
            boolean create = ex == null;

            ObjectNode body = mapper.createObjectNode();
            boolean changed = create;

            // names
            String nameEn = get.apply("name_en"), nameAr = get.apply("name_ar");
            if (create) {
                if (nameEn.isEmpty() && nameAr.isEmpty()) errors.add(new RowError(rowNo, "name_en", "a name is required for a new product"));
                ObjectNode name = mapper.createObjectNode();
                name.put("en", nameEn); name.put("ar", nameAr);
                body.set("name", name);
            } else if (!nameEn.isEmpty() || !nameAr.isEmpty()) {
                String en = nameEn.isEmpty() ? ex.nameEn() : nameEn, ar = nameAr.isEmpty() ? ex.nameAr() : nameAr;
                if (!en.trim().equals(ex.nameEn().trim()) || !ar.trim().equals(ex.nameAr().trim())) {
                    ObjectNode name = mapper.createObjectNode();
                    name.put("en", en); name.put("ar", ar);
                    body.set("name", name);
                    changed = true;
                }
            }

            // descriptions
            String dEn = get.apply("description_en"), dAr = get.apply("description_ar");
            if (!dEn.isEmpty() || !dAr.isEmpty()) {
                String en = create || dEn.isEmpty() ? (create ? dEn : ex.descEn()) : dEn;
                String ar = create || dAr.isEmpty() ? (create ? dAr : ex.descAr()) : dAr;
                if (create || !en.trim().equals(ex.descEn().trim()) || !ar.trim().equals(ex.descAr().trim())) {
                    ObjectNode d = mapper.createObjectNode();
                    d.put("en", en); d.put("ar", ar);
                    body.set("description", d);
                    changed = true;
                }
            }

            // price
            String priceText = get.apply("price");
            if (priceText.isEmpty()) {
                if (create) errors.add(new RowError(rowNo, "price", "price is required for a new product"));
            } else {
                try {
                    BigDecimal price = new BigDecimal(priceText);
                    if (price.signum() < 0) errors.add(new RowError(rowNo, "price", "price cannot be negative"));
                    else if (price.scale() > 2 && price.stripTrailingZeros().scale() > 2)
                        errors.add(new RowError(rowNo, "price", "price can have at most 2 decimals"));
                    else if (price.compareTo(new BigDecimal("9999999999.99")) > 0)
                        errors.add(new RowError(rowNo, "price", "price is too large"));
                    else {
                        price = price.setScale(2, RoundingMode.HALF_UP);
                        if (create || price.compareTo(ex.price()) != 0) { body.put("price", price); changed = true; }
                    }
                } catch (NumberFormatException e) {
                    errors.add(new RowError(rowNo, "price", "price must be a number"));
                }
            }

            // category
            String cat = get.apply("category");
            if (!cat.isEmpty()) {
                List<Long> ids = categoryByName.getOrDefault(cat.toLowerCase(), List.of());
                if (ids.isEmpty()) errors.add(new RowError(rowNo, "category", "category '" + cat + "' does not exist"));
                else if (!create && ex.categoryId() != null && ids.contains(ex.categoryId())) { /* already in it */ }
                else if (ids.size() > 1) errors.add(new RowError(rowNo, "category", "category '" + cat + "' matches more than one category"));
                else { body.put("categoryId", ids.get(0)); changed = true; }
            }

            // active
            String act = get.apply("active").toLowerCase();
            if (!act.isEmpty()) {
                Boolean v = switch (act) {
                    case "true", "1", "yes" -> true;
                    case "false", "0", "no" -> false;
                    default -> null;
                };
                if (v == null) errors.add(new RowError(rowNo, "active", "active must be true, false, 1, 0, yes or no"));
                else if (create || v != ex.active()) { body.put("active", v); changed = true; }
            }

            // tags
            List<String> tags = null;
            String tagText = get.apply("tags");
            if (!tagText.isEmpty()) {
                tags = splitList(tagText);
                if (tags.stream().anyMatch(t -> t.length() > 100)) {
                    errors.add(new RowError(rowNo, "tags", "a tag is longer than 100 characters"));
                } else if (create || !new TreeSet<>(tags).equals(ex.tags())) changed = true;
                else tags = null;
            }

            // additional barcodes
            List<String> newBarcodes = new ArrayList<>();
            for (String b : splitList(get.apply("additional_barcodes"))) {
                if (b.length() > 64) { errors.add(new RowError(rowNo, "additional_barcodes", "barcode '" + b + "' is longer than 64 characters")); continue; }
                if (b.equals(barcode) || (ex != null && ex.extraBarcodes().contains(b))) continue;
                if (claimed.containsKey(b)) {
                    errors.add(new RowError(rowNo, "additional_barcodes", "barcode '" + b + "' also appears in row " + claimed.get(b)));
                    continue;
                }
                if (byBarcode.containsKey(b) || barcodeTaken(b)) {
                    errors.add(new RowError(rowNo, "additional_barcodes", "barcode '" + b + "' belongs to another product"));
                    continue;
                }
                claimed.put(b, rowNo);
                newBarcodes.add(b);
                changed = true;
            }
            if (create && barcodeTaken(barcode)) {
                errors.add(new RowError(rowNo, "barcode", "barcode is already used by another product"));
            }

            if (errors.size() > before) continue;
            if (!changed) { unchanged++; continue; }
            plans.add(new Plan(rowNo, create ? null : ex.id(), barcode, body, tags, newBarcodes, create, true));
        }

        int created = (int) plans.stream().filter(Plan::create).count();
        int updated = plans.size() - created;
        if (!apply || !errors.isEmpty()) {
            return new ImportResult(apply ? "apply" : "validate", created, updated, unchanged, errors);
        }

        tx.executeWithoutResult(status -> {
            for (Plan p : plans) {
                long id;
                if (p.create()) {
                    ObjectNode b = p.body();
                    b.put("barcode", p.barcode());
                    id = products.create(b, orgId);
                } else {
                    id = p.id();
                    ObjectNode b = p.body();
                    if (b.size() > 0) products.patch(id, b);
                }
                if (p.tags() != null) products.syncTags(id, p.tags());
                for (String b : p.newBarcodes()) products.addAlternateBarcode(id, b);
            }
        });
        return new ImportResult("apply", created, updated, unchanged, errors);
    }

    private interface Function3 { String apply(String name); }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String part : s.split(";")) {
            String t = part.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return out;
    }

    // Barcodes are unique across all organizations.
    private boolean barcodeTaken(String barcode) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM product_barcodes WHERE barcode = ?", Integer.class, barcode);
        return n != null && n > 0;
    }
}
