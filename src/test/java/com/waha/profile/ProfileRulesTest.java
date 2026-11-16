package com.waha.profile;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProfileRulesTest {

    @Test
    void phoneIsWrittenInOneStandardForm() {
        for (String raw : new String[]{"0501234567", "+966 50 123 4567", "00966501234567", "501234567", "966501234567", "050-123-4567"})
            assertEquals("+966501234567", ProfileRules.phone(raw, "966"), raw);
        assertEquals("+971501234567", ProfileRules.phone("971501234567", "966"));
        assertEquals("+971501234567", ProfileRules.phone("+971 50 123 4567", "966"));
        assertNull(ProfileRules.phone("  ", "966"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.phone("12", "966"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.phone("abc123456", "966"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.phone("+0501234567", "966"));
    }

    @Test
    void otherFieldsAreCleaned() {
        assertEquals("a@b.com", ProfileRules.email(" A@B.com "));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.email("nope"));
        assertEquals("FEMALE", ProfileRules.gender("female"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.gender("x"));
        assertEquals(LocalDate.of(1990, 5, 17), ProfileRules.birthDate("1990-05-17", LocalDate.of(2026, 11, 16)));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.birthDate("2030-01-01", LocalDate.of(2026, 11, 16)));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.birthDate("17/05/1990", LocalDate.of(2026, 11, 16)));
        assertEquals("ar", ProfileRules.language("ar"));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.language("arabic"));
        assertNull(ProfileRules.name("firstName", "   "));
        assertThrows(IllegalArgumentException.class, () -> ProfileRules.name("firstName", "x".repeat(101)));
    }
}
