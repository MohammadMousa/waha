-- Phase 0: Data integrity — org scoping, composite FKs, scoped unique keys

-- ── 1. categories: unique (company_id, key) ───────────────────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'categories' AND INDEX_NAME = 'uq_categories_company_key');
SET @sql = IF(@s = 0, 'ALTER TABLE categories ADD UNIQUE KEY uq_categories_company_key (company_id, `key`)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 2. system_properties: add organization_id, change PK ──────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'system_properties' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0, 'ALTER TABLE system_properties ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 0 FIRST', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Change PK to (organization_id, key) — existing rows stay at 0 (global)
SET @s = (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'system_properties'
      AND CONSTRAINT_NAME = 'PRIMARY' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0, 'ALTER TABLE system_properties DROP PRIMARY KEY, ADD PRIMARY KEY (organization_id, `key`)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 3. receipt_info: replace store_id with organization_id ────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0, 'ALTER TABLE receipt_info ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 FIRST', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s > 0,
    'UPDATE receipt_info r JOIN stores s ON r.store_id = s.id SET r.organization_id = s.organization_id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @fk = (SELECT CONSTRAINT_NAME FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info'
      AND COLUMN_NAME = 'store_id' AND REFERENCED_TABLE_NAME IS NOT NULL LIMIT 1);
SET @sql = IF(@fk IS NOT NULL, CONCAT('ALTER TABLE receipt_info DROP FOREIGN KEY `', @fk, '`'), 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info'
      AND CONSTRAINT_NAME = 'PRIMARY' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s > 0, 'ALTER TABLE receipt_info DROP PRIMARY KEY', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s > 0, 'ALTER TABLE receipt_info DROP COLUMN store_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Deduplicate: keep one row per organization_id (multiple store receipt rows → one company row).
-- Uses a temp AUTO_INCREMENT column since no unique key exists at this point.
-- Guard: only runs when PK not yet set.
SET @has_pk = (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND CONSTRAINT_NAME = 'PRIMARY');
SET @has_dedup = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND COLUMN_NAME = '_row_num');
SET @sql = IF(@has_pk = 0 AND @has_dedup = 0,
    'ALTER TABLE receipt_info ADD COLUMN _row_num BIGINT NOT NULL AUTO_INCREMENT, ADD KEY k_row_num (_row_num)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @sql = IF(@has_pk = 0 AND @has_dedup = 0,
    'DELETE r FROM receipt_info r WHERE _row_num NOT IN (SELECT min_row FROM (SELECT MIN(_row_num) AS min_row FROM receipt_info GROUP BY organization_id) t)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @sql = IF(@has_pk = 0,
    'ALTER TABLE receipt_info DROP COLUMN _row_num',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_info' AND CONSTRAINT_NAME = 'PRIMARY');
SET @sql = IF(@s = 0,
    'ALTER TABLE receipt_info ADD PRIMARY KEY (organization_id), ADD CONSTRAINT fk_receipt_org FOREIGN KEY (organization_id) REFERENCES organizations(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 4. branch_groups: superkey (id, organization_id) for composite FK ─────────

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'branch_groups' AND INDEX_NAME = 'uq_bg_id_org');
SET @sql = IF(@s = 0, 'ALTER TABLE branch_groups ADD UNIQUE KEY uq_bg_id_org (id, organization_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 5. stores: scoped unique name, composite FK to branch_groups ───────────────

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND INDEX_NAME = 'uq_stores_name');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP INDEX uq_stores_name', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND INDEX_NAME = 'uq_stores_org_name');
SET @sql = IF(@s = 0, 'ALTER TABLE stores ADD UNIQUE KEY uq_stores_org_name (organization_id, name)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Index needed by composite FK
SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND INDEX_NAME = 'idx_stores_bg_org');
SET @sql = IF(@s = 0, 'ALTER TABLE stores ADD INDEX idx_stores_bg_org (branch_group_id, organization_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- Drop single-column FK, replace with composite (branch_group_id, organization_id)
SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND CONSTRAINT_NAME = 'fk_store_bg');
SET @sql = IF(@s > 0, 'ALTER TABLE stores DROP FOREIGN KEY fk_store_bg', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'stores' AND CONSTRAINT_NAME = 'fk_store_bg_org');
SET @sql = IF(@s = 0,
    'ALTER TABLE stores ADD CONSTRAINT fk_store_bg_org FOREIGN KEY (branch_group_id, organization_id) REFERENCES branch_groups(id, organization_id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 6. users: organization_id, scoped unique username ─────────────────────────
-- organization_id = 0 → platform-level (SUPER_ADMIN), no FK (0 has no org row)
-- organization_id ≥ 1 → company user; FK enforced at application layer

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 AFTER id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uq_users_username');
SET @sql = IF(@s > 0, 'ALTER TABLE users DROP INDEX uq_users_username', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'uq_users_org_username');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD UNIQUE KEY uq_users_org_username (organization_id, username)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND INDEX_NAME = 'idx_users_org');
SET @sql = IF(@s = 0, 'ALTER TABLE users ADD INDEX idx_users_org (organization_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 7. user_roles: index (scope_type, scope_id) ───────────────────────────────

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_roles' AND INDEX_NAME = 'idx_ur_scope_type_id');
SET @sql = IF(@s = 0, 'ALTER TABLE user_roles ADD INDEX idx_ur_scope_type_id (scope_type, scope_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 8. orders: organization_id ────────────────────────────────────────────────
-- Populated by trigger trg_orders_before_insert (stored-procs) — DEFAULT 1 is
-- a safe placeholder; the trigger always overrides it from stores.organization_id

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND COLUMN_NAME = 'organization_id');
SET @sql = IF(@s = 0, 'ALTER TABLE orders ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 AFTER store_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE orders o JOIN stores s ON o.store_id = s.id SET o.organization_id = s.organization_id;

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND CONSTRAINT_NAME = 'fk_orders_org');
SET @sql = IF(@s = 0,
    'ALTER TABLE orders ADD CONSTRAINT fk_orders_org FOREIGN KEY (organization_id) REFERENCES organizations(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'orders' AND INDEX_NAME = 'idx_orders_org_created');
SET @sql = IF(@s = 0, 'ALTER TABLE orders ADD INDEX idx_orders_org_created (organization_id, created_at DESC)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 9. payments: store_id + organization_id ───────────────────────────────────
-- Populated by trigger trg_payments_before_insert (stored-procs)

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s = 0,
    'ALTER TABLE payments ADD COLUMN store_id BIGINT NOT NULL DEFAULT 1 AFTER order_id, ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 AFTER store_id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE payments p
JOIN orders o ON p.order_id = o.id
SET p.store_id = o.store_id, p.organization_id = o.organization_id;

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND CONSTRAINT_NAME = 'fk_payments_store');
SET @sql = IF(@s = 0,
    'ALTER TABLE payments ADD CONSTRAINT fk_payments_store FOREIGN KEY (store_id) REFERENCES stores(id), ADD CONSTRAINT fk_payments_org FOREIGN KEY (organization_id) REFERENCES organizations(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payments' AND INDEX_NAME = 'idx_payments_store_date');
SET @sql = IF(@s = 0,
    'ALTER TABLE payments ADD INDEX idx_payments_store_date (store_id, attempted_at DESC), ADD INDEX idx_payments_org_date (organization_id, attempted_at DESC)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

-- ── 10. payment_attempts: store_id + organization_id ──────────────────────────
-- Populated by trigger trg_payment_attempts_before_insert (stored-procs)

SET @s = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND COLUMN_NAME = 'store_id');
SET @sql = IF(@s = 0,
    'ALTER TABLE payment_attempts ADD COLUMN store_id BIGINT NOT NULL DEFAULT 1 AFTER order_id, ADD COLUMN organization_id BIGINT NOT NULL DEFAULT 1 AFTER store_id',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE payment_attempts pa
JOIN orders o ON pa.order_id = o.id
SET pa.store_id = o.store_id, pa.organization_id = o.organization_id;

SET @s = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND CONSTRAINT_NAME = 'fk_pa_store');
SET @sql = IF(@s = 0,
    'ALTER TABLE payment_attempts ADD CONSTRAINT fk_pa_store FOREIGN KEY (store_id) REFERENCES stores(id), ADD CONSTRAINT fk_pa_org FOREIGN KEY (organization_id) REFERENCES organizations(id)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @s = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'payment_attempts' AND INDEX_NAME = 'idx_pa_store_date');
SET @sql = IF(@s = 0,
    'ALTER TABLE payment_attempts ADD INDEX idx_pa_store_date (store_id, created_at DESC), ADD INDEX idx_pa_org_date (organization_id, created_at DESC)',
    'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;
