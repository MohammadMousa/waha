package com.waha.auth;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

// Hashes any plain-text pin_code values left over from before V9 migration.
// Detects unhashed values by the absence of the BCrypt prefix "$2".
// Runs once per startup; no-op if all PINs are already hashed.
@Component
public class PinHashingRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;

    public PinHashingRunner(JdbcTemplate jdbc, BCryptPasswordEncoder encoder) {
        this.jdbc    = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        hashPins("employees");
        hashPins("devices");
    }

    private void hashPins(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT id, pin_code FROM " + table +
            " WHERE pin_code IS NOT NULL AND pin_code NOT LIKE '$2%'"
        );
        for (Map<String, Object> row : rows) {
            long   id    = ((Number) row.get("id")).longValue();
            String plain = (String) row.get("pin_code");
            if (plain != null && !plain.isBlank()) {
                jdbc.update("UPDATE " + table + " SET pin_code = ? WHERE id = ?",
                    encoder.encode(plain), id);
            }
        }
    }
}
