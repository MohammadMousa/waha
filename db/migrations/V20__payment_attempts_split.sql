-- Split payment_attempts out of payments.
-- payments stays for finalized outcomes (PAID / FAILED from simulated / direct flows).
-- payment_attempts is the hot in-flight table: only pending redirect sessions,
-- clearable weekly, tiny, fast for lookups by providerReference.

-- 1. Extend the provider enum before inserting QR_LINK rows.
ALTER TABLE payment_methods MODIFY COLUMN provider ENUM('REDIRECT', 'TERMINAL', 'SIMULATED', 'QR_LINK') NOT NULL;

-- 2. Add offline_capable column if not already present (added in V11/V12 outside core schema).
--    Wrapped in a stored-proc trick so the script is idempotent.
SET @col_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'payment_methods'
      AND column_name = 'offline_capable'
);
SET @sql = IF(@col_exists = 0,
    'ALTER TABLE payment_methods ADD COLUMN offline_capable BOOLEAN NOT NULL DEFAULT FALSE',
    'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3. Create the hot in-flight table (idempotent).
CREATE TABLE IF NOT EXISTS payment_attempts (
    id                 CHAR(36)       NOT NULL PRIMARY KEY,
    order_id           CHAR(36)       NOT NULL,
    provider           VARCHAR(50)    NOT NULL,
    provider_reference VARCHAR(255)   NOT NULL,
    redirect_url       VARCHAR(500)   NOT NULL DEFAULT '',
    qr_data_uri        MEDIUMTEXT     NULL,
    expires_at         DATETIME       NOT NULL,
    created_at         DATETIME(6)    NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX idx_pa_order (order_id),
    INDEX idx_pa_ref   (provider_reference),
    CONSTRAINT fk_pa_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

-- 4. Move existing PENDING redirect records into the new table.
INSERT INTO payment_attempts (id, order_id, provider, provider_reference, expires_at, created_at)
SELECT UUID(), order_id, COALESCE(provider, 'unknown'), provider_reference,
       DATE_ADD(attempted_at, INTERVAL 15 MINUTE), attempted_at
FROM payments
WHERE outcome = 'PENDING'
  AND provider_reference IS NOT NULL;

DELETE FROM payments WHERE outcome = 'PENDING';

-- 5. Seed QR_LINK payment methods for kiosk mode.
--    Keys use _qr suffix to avoid the UNIQUE constraint collision with the
--    existing REDIRECT rows for 'stripe' / 'myfatoorah'. The backend strips
--    the suffix before routing to the provider bean (see OrderService).
INSERT IGNORE INTO payment_methods (`key`, display_name, provider, available_modes, offline_capable, active, sort_order)
VALUES
    ('stripe_qr',     '{"ar": "بطاقة ائتمانية (Stripe)",  "en": "Credit / Debit Card"}', 'QR_LINK', 'KIOSK', FALSE, TRUE, 10),
    ('myfatoorah_qr', '{"ar": "ماي فاتورة",               "en": "MyFatoorah"}',           'QR_LINK', 'KIOSK', FALSE, TRUE, 11);
