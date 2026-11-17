-- Release 5 (api_level 5): everything added since the last deployed version, in one migration.
-- Written to be safe to run again after a failure: tables are created only if missing, rows are copied only
-- while the old table still exists, and permission rows use INSERT IGNORE.

-- 1. Daily data cleanup: remembers when each cleanup task last ran and what it removed.
-- The number of days kept per task lives in system_properties (organization 0).

CREATE TABLE IF NOT EXISTS cleanup_runs (
    task_key     VARCHAR(50)  NOT NULL,
    last_run_at  DATETIME     NOT NULL,
    last_removed BIGINT       NOT NULL DEFAULT 0,
    last_source  VARCHAR(12)  NOT NULL DEFAULT 'SCHEDULED',
    last_error   VARCHAR(255) NULL,
    PRIMARY KEY (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 2. One audit table for system, offer, coupon and role history (replaces system_audit, offer_audit, coupon_audit).
-- scope SYSTEM = super-admin actions (organization_id 0); scope ORGANIZATION = actions inside one organization.

CREATE TABLE IF NOT EXISTS audit_log (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    scope           VARCHAR(12)  NOT NULL,
    organization_id BIGINT       NOT NULL DEFAULT 0,
    entity_type     VARCHAR(30)  NOT NULL,
    entity_id       VARCHAR(64)  DEFAULT NULL,
    actor_user_id   BIGINT       DEFAULT NULL,
    actor_name      VARCHAR(255) DEFAULT NULL,
    action          VARCHAR(50)  NOT NULL,
    target          VARCHAR(255) DEFAULT NULL,
    old_value       MEDIUMTEXT   DEFAULT NULL,
    new_value       MEDIUMTEXT   DEFAULT NULL,
    details         VARCHAR(500) DEFAULT NULL,
    ip              VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_scope_org (scope, organization_id, created_at),
    KEY idx_audit_entity (entity_type, entity_id, created_at),
    KEY idx_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

DROP PROCEDURE IF EXISTS migrate_old_audit;

DELIMITER $$

CREATE PROCEDURE migrate_old_audit()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'system_audit') THEN
    INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                           action, target, old_value, new_value, details, ip)
    SELECT created_at, 'SYSTEM', 0, 'SYSTEM', NULL, actor_user_id, actor_name, action, target, old_value, new_value, details, ip
    FROM system_audit ORDER BY id;
    DROP TABLE system_audit;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'offer_audit') THEN
    INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                           action, old_value, new_value)
    SELECT a.created_at, 'ORGANIZATION', IFNULL(o.organization_id, 0), 'OFFER', CAST(a.offer_id AS CHAR), a.user_id, u.username,
           a.action, CAST(a.before_json AS CHAR), CAST(a.after_json AS CHAR)
    FROM offer_audit a
    LEFT JOIN offers o ON o.id = a.offer_id
    LEFT JOIN users u ON u.id = a.user_id
    ORDER BY a.id;
    DROP TABLE offer_audit;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'coupon_audit') THEN
    INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                           action, old_value, new_value)
    SELECT a.created_at, 'ORGANIZATION', IFNULL(c.organization_id, 0), 'COUPON', CAST(a.coupon_id AS CHAR), a.user_id, u.username,
           a.action, CAST(a.before_json AS CHAR), CAST(a.after_json AS CHAR)
    FROM coupon_audit a
    LEFT JOIN coupons c ON c.id = a.coupon_id
    LEFT JOIN users u ON u.id = a.user_id
    ORDER BY a.id;
    DROP TABLE coupon_audit;
  END IF;
END$$

DELIMITER ;

CALL migrate_old_audit();

DROP PROCEDURE migrate_old_audit;

-- The organization owner may read the organization's own audit.
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, 'VIEW_ORGANIZATION_AUDIT' FROM roles WHERE name = 'ORGANIZATION_OWNER' AND organization_id IS NULL;

-- 3. Customer profile: personal details kept apart from the login account in users.
-- One row per user, created when the person first saves a profile.
CREATE TABLE IF NOT EXISTS user_profiles (
    user_id           BIGINT       NOT NULL,
    organization_id   BIGINT       NOT NULL,
    first_name        VARCHAR(100) DEFAULT NULL,
    last_name         VARCHAR(100) DEFAULT NULL,
    phone             VARCHAR(20)  DEFAULT NULL,
    phone_verified_at TIMESTAMP    NULL DEFAULT NULL,
    email             VARCHAR(255) DEFAULT NULL,
    gender            VARCHAR(10)  DEFAULT NULL,
    birth_date        DATE         DEFAULT NULL,
    language          VARCHAR(5)   DEFAULT NULL,
    avatar_resource_id BIGINT      DEFAULT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_profile_phone (organization_id, phone),
    UNIQUE KEY uq_profile_email (organization_id, email),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_avatar FOREIGN KEY (avatar_resource_id) REFERENCES resources(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 4. Inventory wastes: stock taken out because of expiry, damage, spoilage, missing items and so on.
-- An inventory activity, not a sale or an expense: no price, no cost, no VAT. Saving a waste lowers the branch's
-- stock (store_inventory) in the same transaction. Wastes are never edited or deleted.

CREATE TABLE IF NOT EXISTS inventory_wastes (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    store_id        BIGINT       NOT NULL,
    reason          VARCHAR(20)  NOT NULL,
    occurred_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    employee_id     BIGINT       DEFAULT NULL,
    created_by      BIGINT       DEFAULT NULL,
    created_by_name VARCHAR(255) DEFAULT NULL,
    notes           TEXT         DEFAULT NULL,
    client_ref      VARCHAR(64)  DEFAULT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_wastes_client_ref (organization_id, client_ref),
    KEY idx_wastes_org_time (organization_id, occurred_at),
    KEY idx_wastes_store_time (store_id, occurred_at),
    CONSTRAINT fk_wastes_org      FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_wastes_store    FOREIGN KEY (store_id)        REFERENCES stores(id),
    CONSTRAINT fk_wastes_employee FOREIGN KEY (employee_id)     REFERENCES employees(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS inventory_waste_items (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    waste_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_waste_product (waste_id, product_id),
    KEY idx_waste_items_product (product_id),
    CONSTRAINT fk_wi_waste   FOREIGN KEY (waste_id)   REFERENCES inventory_wastes(id) ON DELETE CASCADE,
    CONSTRAINT fk_wi_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Only the organization owner has the waste permissions by default; the owner gives them to other roles.
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
JOIN (SELECT 'VIEW_WASTES' AS permission UNION ALL SELECT 'CREATE_WASTES') p
WHERE r.name = 'ORGANIZATION_OWNER' AND r.organization_id IS NULL;
