package com.waha.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

// Encrypts secrets (such as the Odoo API key) before they are saved in the database. The key comes from the
// server environment (WAHA_SECRET_KEY), never from the database. Stored form: "enc:v1:" + base64(iv + ciphertext).
// A value without that prefix is read as plain text, so a server without a key keeps working as before.
@Component
public class SecretCipher {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretCipher(@Value("${waha.secret-key:}") String secret) {
        this.key = deriveKey(secret);
    }

    // Visible for tests.
    public static SecretCipher of(String secret) {
        return new SecretCipher(secret);
    }

    public boolean enabled() {
        return key != null;
    }

    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return plain;
        if (isEncrypted(plain)) return plain;
        if (key == null) throw new IllegalStateException("WAHA_SECRET_KEY is not set on this server");
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the value", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !isEncrypted(stored)) return stored;
        if (key == null) throw new IllegalStateException(
            "A saved secret is encrypted but WAHA_SECRET_KEY is not set on this server");
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            if (all.length <= IV_BYTES) throw new IllegalStateException("The saved secret is damaged");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException(
                "A saved secret could not be decrypted: WAHA_SECRET_KEY is not the key it was saved with. Enter the secret again", e);
        }
    }

    // A base64 string of 32 bytes is used as is; anything else is stretched with SHA-256.
    private static SecretKeySpec deriveKey(String secret) {
        if (secret == null || secret.isBlank()) return null;
        String s = secret.trim();
        try {
            byte[] raw = Base64.getDecoder().decode(s);
            if (raw.length == 32) return new SecretKeySpec(raw, "AES");
        } catch (IllegalArgumentException ignored) {
            // not base64: fall through
        }
        try {
            return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
