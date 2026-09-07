-- Phase 3: Auth domain split.
-- employees (POS PIN login) · devices (Kiosk PIN login) · users (admin bcrypt login).
-- Replaces previous V6 / V7 / V8.

-- ── 1. Roles: remove legacy, establish final set ──────────────────────────────
DELETE FROM user_roles WHERE role_id IN (
    SELECT id FROM roles WHERE name IN ('ADMIN','REGISTERED','ANONYMOUS','KIOSK')
);
DELETE FROM roles WHERE name IN ('ADMIN', 'REGISTERED', 'ANONYMOUS', 'KIOSK');

INSERT IGNORE INTO roles (name) VALUES
    ('ORGANIZATION_OWNER'),
    ('BRANCH_ADMIN'),
    ('KIOSK');

-- ── 2. users: drop profile columns (admin-app auth only from here) ────────────
SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='account_type');
SET @s = IF(@c>0,'ALTER TABLE users DROP COLUMN account_type','SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='enabled');
SET @s = IF(@c>0,'ALTER TABLE users DROP COLUMN enabled','SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='first_name');
SET @s = IF(@c>0,'ALTER TABLE users DROP COLUMN first_name','SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='last_name');
SET @s = IF(@c>0,'ALTER TABLE users DROP COLUMN last_name','SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='users' AND COLUMN_NAME='phone');
SET @s = IF(@c>0,'ALTER TABLE users DROP COLUMN phone','SELECT 1');
PREPARE p FROM @s; EXECUTE p; DEALLOCATE PREPARE p;

-- SUPER_ADMIN is platform-level: organization_id = 0
UPDATE users u
JOIN user_roles ur ON ur.user_id = u.id
JOIN roles r ON r.id = ur.role_id AND r.name = 'SUPER_ADMIN'
SET u.organization_id = 0;

-- ── 3. user_roles: add SYSTEM scope, index, fix SUPER_ADMIN rows ─────────────
ALTER TABLE user_roles
    MODIFY COLUMN scope_type ENUM('SYSTEM','COMPANY','BRANCH_GROUP','BRANCH') NOT NULL DEFAULT 'BRANCH';

SET @idx = (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_roles' AND INDEX_NAME = 'idx_ur_user_scope');
SET @sql = IF(@idx = 0, 'ALTER TABLE user_roles ADD INDEX idx_ur_user_scope (user_id, scope_type, scope_id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

UPDATE user_roles ur
JOIN roles r ON r.id = ur.role_id AND r.name = 'SUPER_ADMIN'
SET ur.scope_type = 'SYSTEM', ur.scope_id = 0;

-- ── 4. Drop old employees table if it exists (had user_id FK, now standalone) ─
DROP TABLE IF EXISTS employee_stores;
DROP TABLE IF EXISTS employees;

-- ── 5. employees: standalone POS auth (username + 4-digit pin_code) ──────────
CREATE TABLE employees (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id    BIGINT        NOT NULL,
    username           VARCHAR(100)  NOT NULL,
    pin_code           CHAR(4)       NOT NULL,
    first_name         VARCHAR(100)  DEFAULT NULL,
    last_name          VARCHAR(100)  DEFAULT NULL,
    gender             ENUM('MALE','FEMALE') DEFAULT NULL,
    birth_date         DATE          DEFAULT NULL,
    email              VARCHAR(255)  DEFAULT NULL,
    phone              VARCHAR(30)   DEFAULT NULL,
    hired_at           DATE          DEFAULT NULL,
    address            TEXT          DEFAULT NULL,
    notes              TEXT          DEFAULT NULL,
    avatar_resource_id BIGINT        DEFAULT NULL,
    enabled            TINYINT(1)    NOT NULL DEFAULT 1,
    created_at         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_employees_org_username (organization_id, username),
    INDEX idx_employees_org (organization_id),
    CONSTRAINT fk_employees_org    FOREIGN KEY (organization_id)    REFERENCES organizations(id),
    CONSTRAINT fk_employees_avatar FOREIGN KEY (avatar_resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 6. employee_stores: multi-branch assignment ───────────────────────────────
CREATE TABLE employee_stores (
    employee_id BIGINT NOT NULL,
    store_id    BIGINT NOT NULL,
    PRIMARY KEY (employee_id, store_id),
    INDEX idx_es_store (store_id),
    CONSTRAINT fk_es_employee FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    CONSTRAINT fk_es_store    FOREIGN KEY (store_id)    REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 7. employee_roles: POS-level role assignments (BRANCH_GROUP or BRANCH) ────
DROP TABLE IF EXISTS employee_roles;
CREATE TABLE employee_roles (
    employee_id BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    scope_type  ENUM('BRANCH_GROUP','BRANCH') NOT NULL DEFAULT 'BRANCH',
    scope_id    BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (employee_id, role_id, scope_id),
    INDEX idx_er_scope (scope_type, scope_id),
    CONSTRAINT fk_er_employee FOREIGN KEY (employee_id) REFERENCES employees(id) ON DELETE CASCADE,
    CONSTRAINT fk_er_role     FOREIGN KEY (role_id)     REFERENCES roles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 8. devices: Kiosk App device auth (inherent KIOSK permissions) ───────────
DROP TABLE IF EXISTS devices;
CREATE TABLE devices (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    store_id        BIGINT       NOT NULL,
    device_key      VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    device_type     ENUM('KIOSK','TABLET','SCREEN') NOT NULL DEFAULT 'KIOSK',
    username        VARCHAR(100) NOT NULL,
    pin_code        CHAR(4)      NOT NULL,
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_devices_key (device_key),
    UNIQUE KEY uq_devices_org_username (organization_id, username),
    INDEX idx_devices_org_store (organization_id, store_id),
    CONSTRAINT fk_devices_org   FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_devices_store FOREIGN KEY (store_id)        REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 9. user_sessions: support all three auth domains ─────────────────────────
-- user_id     → admin app (users table)
-- employee_id → POS app (employees table)
-- device_id   → Kiosk app (devices table)
ALTER TABLE user_sessions
    MODIFY COLUMN user_id BIGINT DEFAULT NULL;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND COLUMN_NAME = 'employee_id');
SET @sql = IF(@c = 0, 'ALTER TABLE user_sessions ADD COLUMN employee_id BIGINT DEFAULT NULL AFTER user_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND COLUMN_NAME = 'device_id');
SET @sql = IF(@c = 0, 'ALTER TABLE user_sessions ADD COLUMN device_id BIGINT DEFAULT NULL AFTER employee_id', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND CONSTRAINT_NAME = 'fk_sessions_employee');
SET @sql = IF(@c = 0, 'ALTER TABLE user_sessions ADD CONSTRAINT fk_sessions_employee FOREIGN KEY (employee_id) REFERENCES employees(id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;

SET @c = (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_sessions' AND CONSTRAINT_NAME = 'fk_sessions_device');
SET @sql = IF(@c = 0, 'ALTER TABLE user_sessions ADD CONSTRAINT fk_sessions_device FOREIGN KEY (device_id) REFERENCES devices(id)', 'SELECT 1');
PREPARE p FROM @sql; EXECUTE p; DEALLOCATE PREPARE p;
