-- Phase 2: Resource management — org slug, org-scoped resource directories

-- ── 1. organizations: add slug ────────────────────────────────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND COLUMN_NAME = 'slug');
SET @sql = IF(@s = 0,
    'ALTER TABLE organizations ADD COLUMN slug VARCHAR(100) NULL AFTER name',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Backfill: lowercase-hyphenated name. Runs safely on re-execution (NULL rows only).
UPDATE organizations SET slug = LOWER(REPLACE(name, ' ', '-')) WHERE slug IS NULL;

-- Now enforce NOT NULL + UNIQUE
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations'
      AND COLUMN_NAME = 'slug' AND IS_NULLABLE = 'YES');
SET @sql = IF(@s > 0,
    'ALTER TABLE organizations MODIFY COLUMN slug VARCHAR(100) NOT NULL',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'organizations' AND INDEX_NAME = 'uq_org_slug');
SET @sql = IF(@s = 0,
    'ALTER TABLE organizations ADD UNIQUE KEY uq_org_slug (slug)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 2. resource_directories: org-scoped, optional store scope ─────────────────
-- store_id_key is a NULL-safe generated column (0 = org-level sentinel).
-- MySQL UNIQUE KEY treats NULL != NULL, so (org, NULL, name) would allow
-- duplicates; COALESCE(store_id, 0) makes uniqueness enforceable correctly.

-- 2a. Add organization_id (nullable first, backfill, then enforce NOT NULL)
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD COLUMN organization_id BIGINT NULL AFTER id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE resource_directories rd
JOIN stores s ON rd.store_id = s.id
SET rd.organization_id = s.organization_id
WHERE rd.organization_id IS NULL AND rd.store_id IS NOT NULL;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories'
      AND COLUMN_NAME = 'organization_id' AND IS_NULLABLE = 'YES');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_directories MODIFY COLUMN organization_id BIGINT NOT NULL',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2b. Make store_id nullable (was NOT NULL)
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories'
      AND COLUMN_NAME = 'store_id' AND IS_NULLABLE = 'NO');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_directories MODIFY COLUMN store_id BIGINT DEFAULT NULL',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2c. Plain index on store_id so MySQL uses it (not uq_res_dir) to back fk_res_dir_store.
-- Without this, dropping uq_res_dir later fails with error 1553.
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND INDEX_NAME = 'idx_res_dir_store_id');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD INDEX idx_res_dir_store_id (store_id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2d. Add FK on store_id → stores (was missing from V1)
SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND CONSTRAINT_NAME = 'fk_res_dir_store');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD CONSTRAINT fk_res_dir_store FOREIGN KEY (store_id) REFERENCES stores(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2d. FK on organization_id → organizations
SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND CONSTRAINT_NAME = 'fk_res_dir_org');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD CONSTRAINT fk_res_dir_org FOREIGN KEY (organization_id) REFERENCES organizations(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2e. Add NULL-safe generated column for unique key
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND COLUMN_NAME = 'store_id_key');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD COLUMN store_id_key BIGINT GENERATED ALWAYS AS (COALESCE(store_id, 0)) VIRTUAL',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2f. Drop old unique key (store_id, name), add new (organization_id, store_id_key, name)
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND INDEX_NAME = 'uq_res_dir');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_directories DROP INDEX uq_res_dir',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND INDEX_NAME = 'uq_res_dir_scoped');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD UNIQUE KEY uq_res_dir_scoped (organization_id, store_id_key, name)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 2g. Lookup indexes
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories' AND INDEX_NAME = 'idx_res_dir_org');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_directories ADD INDEX idx_res_dir_org (organization_id), ADD INDEX idx_res_dir_org_store (organization_id, store_id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 3. resource_assets: drop store_id (scope comes from directory) ────────────

-- 3a. Drop FK fk_res_asset_store
SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_assets' AND CONSTRAINT_NAME = 'fk_res_asset_store');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_assets DROP FOREIGN KEY fk_res_asset_store',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 3b. Drop old unique key (store_id, directory_id, name)
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_assets' AND INDEX_NAME = 'uq_res_asset');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_assets DROP INDEX uq_res_asset',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 3c. Drop store_id column
SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_assets' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_assets DROP COLUMN store_id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- 3d. Add scoped unique key (directory_id, name)
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_assets' AND INDEX_NAME = 'uq_res_asset_dir_name');
SET @sql = IF(@s = 0,
    'ALTER TABLE resource_assets ADD UNIQUE KEY uq_res_asset_dir_name (directory_id, name)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;
