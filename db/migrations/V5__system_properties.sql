-- Global server/system configuration — `key`/value pairs for things that are
-- genuinely system-wide, not owned by any single store or entity.
-- Examples: default_store_id, support contact, invoice footer text.
-- Kept intentionally simple: plain VARCHAR value, no typing. Callers parse
-- what they need (e.g. CAST to BIGINT for default_store_id). Admin sets
-- values directly in the DB for now; a management API can be added later.
CREATE TABLE system_properties (
    `key`          VARCHAR(100)  PRIMARY KEY,
    value        VARCHAR(500)  NOT NULL,
    description  VARCHAR(255)  NULL
) ENGINE=InnoDB;

-- The store clients fall back to when no local store is configured on the
-- device (fresh install, first launch). Kiosk devices get their own store
-- assigned locally by an operator; this is the fallback for Normal-mode
-- browsers and fresh Shopping sessions only.
INSERT INTO system_properties (`key`, value, description) VALUES
    ('default_store_id', '5', 'Fallback store returned with every auth response (login/register/guest/me)');
