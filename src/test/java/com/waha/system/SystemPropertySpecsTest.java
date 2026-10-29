package com.waha.system;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SystemPropertySpecsTest {

    @Test
    void baseUrlIsTrimmedAndLosesTrailingSlash() {
        assertEquals("https://waha1.duckdns.org", SystemPropertySpecs.clean("publicBaseUrl", " https://waha1.duckdns.org/ "));
        assertEquals("", SystemPropertySpecs.clean("publicBaseUrl", "  "));
    }

    @Test
    void baseUrlMustBeAnHttpAddress() {
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("publicBaseUrl", "waha1.duckdns.org"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("publicBaseUrl", "https://a b"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("publicBaseUrl", "https://"));
    }

    @Test
    void numbersAreRangeChecked() {
        assertEquals("0", SystemPropertySpecs.clean("Account_Sessions_Cap", "0"));
        assertEquals("1000", SystemPropertySpecs.clean("Account_Sessions_Cap", "1000"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Account_Sessions_Cap", "1001"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Account_Sessions_Cap", "abc"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("last_seen_flush_minutes", "0"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("resource.max_size_bytes", "10"));
    }

    @Test
    void booleanIsLowerCased() {
        assertEquals("true", SystemPropertySpecs.clean("CentralProductSystem", "TRUE"));
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("CentralProductSystem", "yes"));
    }

    @Test
    void unknownKeyIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> SystemPropertySpecs.clean("Log_Level", "Info"));
    }
}
