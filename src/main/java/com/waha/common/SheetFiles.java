package com.waha.common;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

// Builds CSV and XLSX download files from rows of text. Every cell is text, so barcodes keep their leading zeros.
public final class SheetFiles {

    private SheetFiles() {}

    public static byte[] csv(List<String> columns, List<List<String>> rows) {
        StringBuilder sb = new StringBuilder("﻿");          // byte order mark so Excel reads the Arabic correctly
        sb.append(String.join(",", columns.stream().map(SheetFiles::cell).toList())).append("\r\n");
        for (List<String> r : rows) {
            StringJoiner j = new StringJoiner(",");
            for (String c : r) j.add(cell(c));
            sb.append(j).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] xlsx(String sheetName, List<String> columns, List<List<String>> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet(sheetName);
            Row header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) header.createCell(c).setCellValue(columns.get(c));
            for (int i = 0; i < rows.size(); i++) {
                Row r = sheet.createRow(i + 1);
                List<String> v = rows.get(i);
                for (int c = 0; c < columns.size(); c++) r.createCell(c).setCellValue(c < v.size() && v.get(c) != null ? v.get(c) : "");
            }
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the XLSX file", e);
        }
    }

    static String cell(String c) {
        if (c == null) return "";
        boolean quote = c.contains(",") || c.contains("\"") || c.contains("\n") || c.contains("\r");
        return quote ? "\"" + c.replace("\"", "\"\"") + "\"" : c;
    }
}
