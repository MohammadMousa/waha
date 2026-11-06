-- Offline mode (see docs/offline.md). One migration for everything the kiosk's offline mode needs.

-- 1. Pull feeds ask "what changed since X" per table, so every synced table needs a change time.
--    The column refreshes itself only when a value really changes. Existing rows get the migration time.
ALTER TABLE categories
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE payment_methods
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE payment_methods_store
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
ALTER TABLE product_images
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- 2. Devices: a number (1..99) unique inside the store, used in the order reference, plus the sync state
--    each kiosk reports for the dashboard.
ALTER TABLE devices
    ADD COLUMN device_no TINYINT UNSIGNED NULL,
    ADD COLUMN last_pull_at DATETIME NULL,
    ADD COLUMN last_push_at DATETIME NULL,
    ADD COLUMN pending_orders INT NULL,
    ADD COLUMN failed_orders INT NULL;

UPDATE devices d
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY store_id ORDER BY id) AS rn FROM devices) x ON x.id = d.id
SET d.device_no = x.rn;

ALTER TABLE devices
    MODIFY COLUMN device_no TINYINT UNSIGNED NOT NULL,
    ADD CONSTRAINT chk_devices_device_no CHECK (device_no BETWEEN 1 AND 99),
    ADD UNIQUE KEY uq_devices_store_no (store_id, device_no);

-- 3. Stores: optional short prefix for order references (for example WH, OSS). Empty is fine.
ALTER TABLE stores ADD COLUMN short_code VARCHAR(10) NULL;

-- 4. Orders: lines get a stable line number; orders remember they came from an offline kiosk, which
--    kiosk, and the reference a customer can quote before the order reached the server.
ALTER TABLE order_items ADD COLUMN line_no INT NULL;

UPDATE order_items oi
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY id) AS rn FROM order_items) x ON x.id = oi.id
SET oi.line_no = x.rn;

ALTER TABLE order_items
    MODIFY COLUMN line_no INT NOT NULL,
    ADD UNIQUE KEY uq_order_items_line (order_id, line_no);

ALTER TABLE orders
    ADD COLUMN offline_pushed  TINYINT(1)  NOT NULL DEFAULT 0,
    ADD COLUMN price_differs   TINYINT(1)  NOT NULL DEFAULT 0,
    ADD COLUMN order_reference VARCHAR(30) NULL,
    ADD COLUMN device_id       BIGINT      NULL,
    ADD KEY idx_orders_reference (store_id, order_reference),
    ADD KEY idx_orders_device (device_id, created_at);

-- 5. A terminal payment can only be recorded once per order, provider and reference.
ALTER TABLE payments
    ADD UNIQUE KEY uq_payments_order_ref (order_id, provider, provider_reference);
