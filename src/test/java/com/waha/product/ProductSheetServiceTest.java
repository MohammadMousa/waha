package com.waha.product;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductSheetServiceTest {

    @Test
    void csvHandlesBomQuotesCommasAndNewlines() {
        String csv = "﻿barcode,name_en,name_ar\r\n001,\"Tea, black\",شاي\r\n002,\"Say \"\"hi\"\"\",\"two\nlines\"\r\n";
        List<List<String>> rows = ProductSheetService.readCsv(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, rows.size());
        assertEquals(List.of("barcode", "name_en", "name_ar"), rows.get(0));
        assertEquals(List.of("001", "Tea, black", "شاي"), rows.get(1));
        assertEquals(List.of("002", "Say \"hi\"", "two\nlines"), rows.get(2));
    }

    @Test
    void csvKeepsEmptyCellsAndLastLineWithoutNewline() {
        List<List<String>> rows = ProductSheetService.readCsv("a,b,c\n1,,3".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of("1", "", "3"), rows.get(1));
    }
}
