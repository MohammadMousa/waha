package com.waha.coupon;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CodeGeneratorTest {

    @Test
    void codeHasThePrefixAndEightSafeCharacters() {
        String code = CodeGenerator.generate("EMP");
        assertTrue(code.matches("EMP-[A-HJ-NP-Z2-9]{8}"), code);
        assertTrue(CodeGenerator.generate("").matches("[A-HJ-NP-Z2-9]{8}"));
        assertTrue(CodeGenerator.generate(null).matches("[A-HJ-NP-Z2-9]{8}"));
    }

    @Test
    void manyCodesAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 2000; i++) seen.add(CodeGenerator.generate("X"));
        assertEquals(2000, seen.size());
    }
}
