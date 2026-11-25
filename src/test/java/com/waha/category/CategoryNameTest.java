package com.waha.category;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CategoryNameTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static String name(String json) throws Exception {
        return CategoryAdminController.nameJson(M.readTree(json));
    }

    @Test
    void keepsBothTexts() throws Exception {
        assertEquals("{\"ar\":\"مشروبات\",\"en\":\"Drinks\"}", name("{\"ar\":\" مشروبات \",\"en\":\"Drinks\",\"x\":\"y\"}"));
    }

    @Test
    void oneTextIsEnough() throws Exception {
        assertEquals("{\"ar\":\"\",\"en\":\"Drinks\"}", name("{\"en\":\"Drinks\"}"));
    }

    @Test
    void rejectsEmptyOrWrongShape() throws Exception {
        assertNull(name("{\"ar\":\" \",\"en\":\"\"}"));
        assertNull(name("\"Drinks\""));
        assertNull(name("{\"en\":5}"));
        assertNull(CategoryAdminController.nameJson(null));
        assertNull(name("{\"en\":\"" + "a".repeat(101) + "\"}"));
    }
}
