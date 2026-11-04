-- Barcodes that kiosks scanned but the catalog did not have: one row per store and barcode with a running count.
-- Replaces one-row-per-scan product_scan_misses (kept as history, no longer written).
CREATE TABLE product_missing (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    store_id        BIGINT       NOT NULL,
    barcode         VARCHAR(64)  NOT NULL,
    miss_count      BIGINT       NOT NULL DEFAULT 0,
    first_missed_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_missed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_username   VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_product_missing (store_id, barcode),
    KEY idx_product_missing_last (last_missed_at),
    CONSTRAINT fk_product_missing_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;  -- same collation as product_barcodes

INSERT INTO product_missing (store_id, barcode, miss_count, first_missed_at, last_missed_at)
SELECT store_id, barcode, COUNT(*), MIN(scanned_at), MAX(scanned_at)
FROM product_scan_misses GROUP BY store_id, barcode;
