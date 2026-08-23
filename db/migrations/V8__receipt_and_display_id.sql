-- Per-store receipt branding. One row per store. Used when generating PDF
-- invoices (Phase C). name_ar / name_en are separate columns (not JSON)
-- because ZATCA invoice generation needs direct string access, not blob
-- parsing. logo_resource_id will be wired to a resources table in Phase C;
-- it is nullable here so stores can be configured before resource upload
-- is implemented.
CREATE TABLE receipt_info (
    store_id        BIGINT       PRIMARY KEY,
    name_ar         VARCHAR(255) NOT NULL,
    name_en         VARCHAR(255) NOT NULL,
    address_text    TEXT         NULL,   -- free-form formatted address line for receipt footer
    vat_number      VARCHAR(50)  NULL,   -- 15-digit ZATCA VAT number
    cr_number       VARCHAR(50)  NULL,   -- commercial registration number
    logo_resource_id BIGINT      NULL,   -- Phase C: FK to resources(id), nullable until then
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_receipt_info_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;

-- Human-readable order number, unique per store. Separate from orders.id
-- (UUID, for idempotency/offline safety) - display_id is what a cashier
-- reads aloud or prints on the receipt: "Order #100042".
--
-- The sequence table avoids race conditions: an atomic UPDATE + SELECT
-- produces the next value without a read-modify-write gap. One row per
-- store, created on first order creation for that store.
CREATE TABLE store_order_sequences (
    store_id    BIGINT NOT NULL PRIMARY KEY,
    `last_value`  BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_seq_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;

-- display_id: 6-8 digit integer, store-unique. NULL until the first time
-- the ordering flow runs through the new sequence logic (existing rows
-- keep NULL; the invoice renderer shows the UUID short-form as fallback).
ALTER TABLE orders
    ADD COLUMN display_id BIGINT NULL AFTER id,
    ADD UNIQUE KEY uq_orders_display_id (store_id, display_id);
