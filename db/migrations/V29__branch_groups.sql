-- Organizational hierarchy refactoring: Company → Branch Group → Branch
--
-- organizations(id=1 COMPANY) and organizations(id=2 BRANCH_GROUP delta)
-- already exist; stores.organization_id is already wired correctly.
-- This migration completes the refactor:
--   1. Migrates data off the 4 stores being removed (1,2,3,4)
--   2. Refactors user_roles to scope_type + scope_id (no store FK for company scope)
--   3. Removes PARENT/WAREHOUSE store rows and their now-redundant columns

-- ── Step 1: Products — redirect order history off duplicates, then null scope ─

-- Products 8 and 9 are scoped to stores 2/3 but share barcodes with
-- global products 3 and 5. Redirect order_items first so the FK allows deletion.
UPDATE order_items SET product_id = 3 WHERE product_id = 8;
UPDATE order_items SET product_id = 5 WHERE product_id = 9;
DELETE FROM products WHERE id IN (8, 9);

UPDATE products
SET scope_store_id = NULL, scope_store_type = NULL
WHERE scope_store_id IN (1, 2, 3, 4);

-- ── Step 2: Categories ────────────────────────────────────────────────────────

UPDATE categories
SET scope_store_id = NULL
WHERE scope_store_id IN (1, 2, 3, 4);

-- ── Step 3: Orders — move store-1 orders to oasis, renumber to avoid collision ─

SET @oasis_max = (SELECT COALESCE(MAX(display_id), 0) FROM orders WHERE store_id = 7);
UPDATE orders
SET store_id   = 7,
    display_id = display_id + @oasis_max
WHERE store_id = 1;

-- ── Step 4: Payment methods — inherit store-1 definitions at all branches ─────

INSERT IGNORE INTO payment_methods_store (store_id, payment_method_id, active, sort_order)
SELECT b.id, pms.payment_method_id, pms.active, pms.sort_order
FROM payment_methods_store pms
CROSS JOIN (SELECT id FROM stores WHERE id IN (5, 6, 7)) b
WHERE pms.store_id = 1;
DELETE FROM payment_methods_store WHERE store_id = 1;

-- ── Step 5: Resource directories — drop store FK if still present ─────────────

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'resource_directories'
    AND CONSTRAINT_NAME = 'fk_res_dir_store');
SET @sql = IF(@s > 0,
    'ALTER TABLE resource_directories DROP FOREIGN KEY fk_res_dir_store',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── Step 6: Clear sessions and tags on removed stores ─────────────────────────

UPDATE user_sessions SET store_id = NULL WHERE store_id IN (1, 2, 3, 4);
DELETE FROM store_tags WHERE store_id IN (1, 2, 3, 4);

-- ── Step 7: user_roles — drop store FK, stamp scope_type ─────────────────────
-- scope_type COMPANY/BRANCH_GROUP → scope_id references organizations.id
-- scope_type BRANCH               → scope_id references stores.id
-- FK is dropped so company-scope rows can safely reference org id=1 via store_id=1

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_roles'
    AND CONSTRAINT_NAME = 'fk_ur_store');
SET @sql = IF(@s > 0,
    'ALTER TABLE user_roles DROP FOREIGN KEY fk_ur_store',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_roles'
    AND COLUMN_NAME = 'scope_type');
SET @sql = IF(@s = 0,
    'ALTER TABLE user_roles ADD COLUMN scope_type ENUM(''COMPANY'',''BRANCH_GROUP'',''BRANCH'') NOT NULL DEFAULT ''BRANCH'' AFTER store_id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE user_roles SET scope_type = 'COMPANY' WHERE store_id = 1;
UPDATE user_roles SET scope_type = 'BRANCH'  WHERE store_id IN (5, 6, 7);

-- ── Step 8: Delete removed stores (keep store 1 as virtual company anchor) ────

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores'
    AND CONSTRAINT_NAME = 'fk_stores_parent');
SET @sql = IF(@s > 0,
    'ALTER TABLE stores DROP FOREIGN KEY fk_stores_parent',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

DELETE FROM stores WHERE id IN (2, 3, 4);

-- ── Step 9: Drop columns no longer needed on stores ──────────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores'
    AND CONSTRAINT_NAME = 'chk_stores_root');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP CONSTRAINT chk_stores_root', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores'
    AND CONSTRAINT_NAME = 'chk_stores_kind');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP CONSTRAINT chk_stores_kind', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND COLUMN_NAME = 'store_type');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP COLUMN store_type', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND COLUMN_NAME = 'store_kind');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP COLUMN store_kind', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND COLUMN_NAME = 'parent_store_id');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP COLUMN parent_store_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND COLUMN_NAME = 'path');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP COLUMN path', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── Step 10: users — add account_type, enabled, and profile fields ───────────

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'account_type');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN account_type VARCHAR(10) NOT NULL DEFAULT ''HUMAN''', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'enabled');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN enabled TINYINT(1) NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'first_name');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN first_name VARCHAR(100) NULL', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'last_name');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN last_name VARCHAR(100) NULL', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'phone');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN phone VARCHAR(30) NULL', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;
