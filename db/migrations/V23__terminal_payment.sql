-- Terminal payment support:
-- 1. Add auth_code + notes to payment_attempts (no new table needed)
-- 2. Add status column to payment_attempts for terminal pending/confirmed/timeout tracking
-- 3. Extend provider ENUM to include TERMINAL if not already present
-- 4. Seed terminal payment method

-- 1. Add status to payment_attempts (NULL = redirect/QR flow, not applicable)
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'status');
SET @sql = IF(@col = 0,
    'ALTER TABLE payment_attempts ADD COLUMN status VARCHAR(20) NULL COMMENT ''PENDING|CONFIRMED|CANCELLED|TIMEOUT — terminal sessions only''',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 2. Add auth_code — structured, always meaningful when present
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'auth_code');
SET @sql = IF(@col = 0,
    'ALTER TABLE payment_attempts ADD COLUMN auth_code VARCHAR(20) NULL',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 3. Add notes JSON — card_uid, brand, last4, expiry, entry_method, terminal_id, response_code
--    content driven by terminal debug level; columns graduate here when proven critical
SET @col = (SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'notes');
SET @sql = IF(@col = 0,
    'ALTER TABLE payment_attempts ADD COLUMN notes JSON NULL',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 4. Extend provider ENUM to include TERMINAL
SET @enum_ok = (SELECT LOCATE('TERMINAL', COLUMN_TYPE) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_methods' AND COLUMN_NAME = 'provider');
SET @sql = IF(@enum_ok = 0,
    'ALTER TABLE payment_methods MODIFY COLUMN provider ENUM(''REDIRECT'',''TERMINAL'',''SIMULATED'',''QR_LINK'',''PAYMENT_URL'') NOT NULL',
    'SELECT 1');
PREPARE s FROM @sql; EXECUTE s; DEALLOCATE PREPARE s;

-- 5. Seed terminal payment method (disabled by default — admin enables per store)
INSERT IGNORE INTO payment_methods (`key`, display_name, provider, available_modes, offline_capable, active, sort_order)
VALUES ('terminal',
        '{"en":"Terminal Payment","ar":"جهاز الدفع"}',
        'TERMINAL',
        'KIOSK',
        TRUE,
        FALSE,
        90);
