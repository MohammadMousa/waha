-- Release 7 (api_level 7): everything added after release 6, in one migration.
-- Safe to run again after a failure: the table is created only if missing.

-- 1. Sync table settings per organization: switch a table off and/or give it an expiration period (docs/sync-combined-pull.md).
--    No row for a table means ON with no expiration period.
CREATE TABLE IF NOT EXISTS sync_table_config (
    organization_id   BIGINT       NOT NULL,
    table_name        VARCHAR(40)  NOT NULL COMMENT 'products, barcodes, categories, payment-methods, store-payment-methods, product-images or removals',
    active            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 = skipped by the pull',
    expiration_period VARCHAR(10)  NULL COMMENT 'NULL = checked at every pull; else <number><S|M|H|D> like 30S, 5M, 2H, 1D',
    description       VARCHAR(255) NULL COMMENT 'Note for admins',
    created_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (organization_id, table_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
