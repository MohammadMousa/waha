package com.waha.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCipherTest {

    private static final String KEY = "pF3lO1m3k9y0m1d9Qx0R0n8d0m4K3y4Zq1w2e3r4t5y=";

    @Test
    void roundTripAndEveryEncryptionDiffers() {
        SecretCipher c = SecretCipher.of(KEY);
        String a = c.encrypt("odoo-api-key-123");
        String b = c.encrypt("odoo-api-key-123");
        assertTrue(a.startsWith(SecretCipher.PREFIX));
        assertNotEquals(a, b);
        assertEquals("odoo-api-key-123", c.decrypt(a));
        assertEquals("odoo-api-key-123", c.decrypt(b));
        assertEquals("ключ-مفتاح", c.decrypt(c.encrypt("ключ-مفتاح")));
    }

    @Test
    void plainTextAndEmptyValuesPassThrough() {
        SecretCipher c = SecretCipher.of(KEY);
        assertEquals("plain", c.decrypt("plain"));
        assertNull(c.decrypt(null));
        assertEquals("", c.encrypt(""));
        assertNull(c.encrypt(null));
        String once = c.encrypt("x");
        assertEquals(once, c.encrypt(once));
    }

    @Test
    void wrongKeyOrDamagedValueIsRefused() {
        String stored = SecretCipher.of(KEY).encrypt("secret");
        assertThrows(IllegalStateException.class, () -> SecretCipher.of("another key entirely").decrypt(stored));
        assertThrows(IllegalStateException.class, () -> SecretCipher.of(KEY).decrypt(stored.substring(0, stored.length() - 4) + "AAAA"));
        assertThrows(IllegalStateException.class, () -> SecretCipher.of(KEY).decrypt(SecretCipher.PREFIX + "AAAA"));
    }

    @Test
    void withoutAKeyPlainTextStillWorksButEncryptedValuesAreRefused() {
        SecretCipher none = SecretCipher.of("");
        assertFalse(none.enabled());
        assertEquals("plain", none.decrypt("plain"));
        assertThrows(IllegalStateException.class, () -> none.encrypt("x"));
        assertThrows(IllegalStateException.class, () -> none.decrypt(SecretCipher.of(KEY).encrypt("x")));
    }

    @Test
    void anyTextWorksAsAKey() {
        SecretCipher c = SecretCipher.of("just a long passphrase");
        assertEquals("v", c.decrypt(c.encrypt("v")));
    }
}
