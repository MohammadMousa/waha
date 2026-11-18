-- Release 6 (api_level 6): everything added after release 5, in one migration.
-- Safe to run again after a failure: the column is added only if missing and permission rows use INSERT IGNORE.

-- 1. Odoo account: create products that are not in Odoo when an order is pushed (off by default).
SET @col = (SELECT COUNT(*) FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = 'external_systems' AND column_name = 'create_missing_products');
SET @sql = IF(@col = 0,
    'ALTER TABLE external_systems ADD COLUMN create_missing_products TINYINT(1) NOT NULL DEFAULT 0',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. Deleting unpaid orders by hand: the organization owner has the permission by default.
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, 'DELETE_UNPAID_ORDERS' FROM roles WHERE name = 'ORGANIZATION_OWNER' AND organization_id IS NULL;
