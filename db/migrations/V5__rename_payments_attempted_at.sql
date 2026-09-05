-- Rename payments.attempted_at → created_at

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'attempted_at');
SET @sql = IF(@s > 0,
    'ALTER TABLE payments CHANGE COLUMN attempted_at created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Add plain single-column indexes FIRST so FKs have a backing index when compound ones are dropped.
-- fk_payments_store uses idx_payments_store_date(store_id, ...) as its backing index.
-- fk_payments_org  uses idx_payments_org_date(organization_id, ...) as its backing index.
-- Without plain indexes, dropping the compound ones fails with error 1553.

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_store_id');
SET @sql = IF(@s = 0, 'ALTER TABLE payments ADD INDEX idx_payments_store_id (store_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_org_id');
SET @sql = IF(@s = 0, 'ALTER TABLE payments ADD INDEX idx_payments_org_id (organization_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Now safe to drop the old compound indexes

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_store_date');
SET @sql = IF(@s > 0, 'ALTER TABLE payments DROP INDEX idx_payments_store_date', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_org_date');
SET @sql = IF(@s > 0, 'ALTER TABLE payments DROP INDEX idx_payments_org_date', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Add proper compound indexes on created_at

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_store_created');
SET @sql = IF(@s = 0,
    'ALTER TABLE payments ADD INDEX idx_payments_store_created (store_id, created_at DESC), ADD INDEX idx_payments_org_created (organization_id, created_at DESC)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Drop the now-redundant plain indexes (compound ones cover the FK backing)

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_store_id');
SET @sql = IF(@s > 0, 'ALTER TABLE payments DROP INDEX idx_payments_store_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_org_id');
SET @sql = IF(@s > 0, 'ALTER TABLE payments DROP INDEX idx_payments_org_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;
