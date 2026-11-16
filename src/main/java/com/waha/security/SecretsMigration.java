package com.waha.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// On start, encrypts secrets that were saved as plain text before the server had an encryption key.
// Safe to run every start: values that are already encrypted are skipped.
@Component
public class SecretsMigration {

    private static final Logger log = LoggerFactory.getLogger(SecretsMigration.class);

    private final JdbcTemplate jdbc;
    private final SecretCipher cipher;

    public SecretsMigration(JdbcTemplate jdbc, SecretCipher cipher) {
        this.jdbc = jdbc;
        this.cipher = cipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, api_key FROM external_systems WHERE api_key IS NOT NULL AND api_key <> '' AND api_key NOT LIKE ?",
                SecretCipher.PREFIX + "%");
            if (rows.isEmpty()) return;
            if (!cipher.enabled()) {
                log.warn("{} saved secret(s) are stored as plain text. Set WAHA_SECRET_KEY in the server .env and restart to encrypt them",
                    rows.size());
                return;
            }
            for (Map<String, Object> r : rows) {
                jdbc.update("UPDATE external_systems SET api_key = ? WHERE id = ?",
                    cipher.encrypt((String) r.get("api_key")), r.get("id"));
            }
            log.info("Encrypted {} saved secret(s)", rows.size());
        } catch (RuntimeException e) {
            log.warn("Could not check saved secrets: {}", e.getMessage());
        }
    }
}
