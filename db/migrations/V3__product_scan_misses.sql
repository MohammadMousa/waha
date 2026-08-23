-- Purely additive - doesn't touch any existing table. A barcode that
-- doesn't resolve at all isn't a business-rule question the system can
-- adjudicate (it has no price to charge), but the failure shouldn't be a
-- dead end either - logging every miss is what a future "missing barcodes"
-- report needs, so a real data-entry gap (stocked before it was entered
-- into the system) gets fixed fast instead of just erroring silently over
-- and over at the register.
CREATE TABLE product_scan_misses (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode     VARCHAR(64) NOT NULL,
    store_id    BIGINT NOT NULL,
    scanned_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_scan_misses_store FOREIGN KEY (store_id) REFERENCES stores(id),
    INDEX idx_scan_misses_barcode (barcode)
) ENGINE=InnoDB;
