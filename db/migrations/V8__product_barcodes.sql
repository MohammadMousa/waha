CREATE TABLE product_barcodes (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    product_id BIGINT      NOT NULL,
    barcode    VARCHAR(64) NOT NULL,
    is_primary TINYINT(1)  NOT NULL DEFAULT 0,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_product_barcodes_barcode (barcode),
    KEY idx_product_barcodes_product (product_id),
    CONSTRAINT fk_product_barcodes_product
        FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
);

INSERT INTO product_barcodes (product_id, barcode, is_primary)
SELECT id, barcode, 1
FROM products
WHERE barcode IS NOT NULL AND barcode != '';
