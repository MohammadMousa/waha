-- 1. Extend provider ENUM to include PAYMENT_URL (mobile handoff QR).
--    MODIFY COLUMN is idempotent — safe to re-run if PAYMENT_URL already present.
SET @enum_ok = (SELECT LOCATE('PAYMENT_URL', COLUMN_TYPE) FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_methods' AND COLUMN_NAME = 'provider');
SET @sql1 = IF(@enum_ok = 0,
    'ALTER TABLE payment_methods MODIFY COLUMN provider ENUM(''REDIRECT'',''TERMINAL'',''SIMULATED'',''QR_LINK'',''PAYMENT_URL'') NOT NULL',
    'SELECT 1 -- provider ENUM already has PAYMENT_URL');
PREPARE stmt FROM @sql1; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2. Add payment_url column (only populated for PAYMENT_URL provider rows).
--    ADD COLUMN IF NOT EXISTS is MariaDB-only; use info_schema check for MySQL.
SET @col_ok = (SELECT COUNT(*) FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_methods' AND COLUMN_NAME = 'payment_url');
SET @sql2 = IF(@col_ok = 0,
    'ALTER TABLE payment_methods ADD COLUMN payment_url VARCHAR(500) NULL',
    'SELECT 2 -- payment_url column already exists');
PREPARE stmt FROM @sql2; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Fix Stripe English display name (item 1 of batch).
UPDATE payment_methods
SET display_name = '{"ar":"بطاقة ائتمانية (Stripe)","en":"Stripe"}'
WHERE `key` = 'stripe';

-- 4. Seed Mobile Payment method (KIOSK only, PAYMENT_URL provider).
--    payment_url must be set by the operator to the web-app shopping URL.
INSERT IGNORE INTO payment_methods (`key`, display_name, provider, available_modes, active, sort_order, payment_url)
VALUES ('mobile_payment',
        '{"ar":"الدفع عبر الجوال","en":"Mobile Payment"}',
        'PAYMENT_URL',
        'KIOSK',
        TRUE,
        10,
        '');
