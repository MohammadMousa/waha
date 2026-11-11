-- OPEN migration: not deployed yet. More database changes for this release are added here until it ships.

-- Barcodes of missing products must sort like product_barcodes does on a database built from the migrations.
-- (Production was already changed by hand; this is then a no-op.)
ALTER TABLE product_missing
    MODIFY barcode VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL;

-- Customer addresses: the existing addresses table now also holds addresses that belong to a user.
-- A deleted address is only hidden, so it can never disappear from an order that used it.
ALTER TABLE addresses
    ADD COLUMN user_id        BIGINT       DEFAULT NULL AFTER id,
    ADD COLUMN label          VARCHAR(60)  DEFAULT NULL,
    ADD COLUMN recipient_name VARCHAR(255) DEFAULT NULL,
    ADD COLUMN phone          VARCHAR(30)  DEFAULT NULL,
    ADD COLUMN is_default     TINYINT(1)   NOT NULL DEFAULT 0,
    ADD COLUMN deleted_at     TIMESTAMP    NULL DEFAULT NULL,
    ADD KEY idx_addresses_user (user_id, deleted_at),
    ADD CONSTRAINT fk_addresses_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;

-- How an online order is received. Orders from kiosks and the shopping mode keep these empty (no method, no shipping).
-- shipping_address is a copy of the address at order time, so later edits never change an old order.
-- shipping_amount is added to the order total once paid shipping exists; today every method is free.
ALTER TABLE orders
    ADD COLUMN shipping_method     VARCHAR(30)   DEFAULT NULL,
    ADD COLUMN shipping_amount     DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN shipping_address_id BIGINT        DEFAULT NULL,
    ADD COLUMN shipping_address    JSON          DEFAULT NULL;
