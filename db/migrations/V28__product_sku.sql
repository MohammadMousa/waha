-- Internal product code / SKU shown in reports (e.g. "MOD-151")
ALTER TABLE products
    ADD COLUMN sku VARCHAR(50) NULL AFTER barcode;
