-- One audit table for system, offer, coupon and role history (replaces system_audit, offer_audit, coupon_audit).
-- scope SYSTEM = super-admin actions (organization_id 0); scope ORGANIZATION = actions inside one organization.

CREATE TABLE audit_log (
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

INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                       action, target, old_value, new_value, details, ip)
SELECT created_at, 'SYSTEM', 0, 'SYSTEM', NULL, actor_user_id, actor_name, action, target, old_value, new_value, details, ip
FROM system_audit ORDER BY id;

INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                       action, old_value, new_value)
SELECT a.created_at, 'ORGANIZATION', IFNULL(o.organization_id, 0), 'OFFER', CAST(a.offer_id AS CHAR), a.user_id, u.username,
       a.action, CAST(a.before_json AS CHAR), CAST(a.after_json AS CHAR)
FROM offer_audit a
LEFT JOIN offers o ON o.id = a.offer_id
LEFT JOIN users u ON u.id = a.user_id
ORDER BY a.id;

INSERT INTO audit_log (created_at, scope, organization_id, entity_type, entity_id, actor_user_id, actor_name,
                       action, old_value, new_value)
SELECT a.created_at, 'ORGANIZATION', IFNULL(c.organization_id, 0), 'COUPON', CAST(a.coupon_id AS CHAR), a.user_id, u.username,
       a.action, CAST(a.before_json AS CHAR), CAST(a.after_json AS CHAR)
FROM coupon_audit a
LEFT JOIN coupons c ON c.id = a.coupon_id
LEFT JOIN users u ON u.id = a.user_id
ORDER BY a.id;

DROP TABLE system_audit;
DROP TABLE offer_audit;
DROP TABLE coupon_audit;

-- The organization owner may read the organization's own audit.
INSERT IGNORE INTO role_permissions (role_id, permission)
SELECT id, 'VIEW_ORGANIZATION_AUDIT' FROM roles WHERE name = 'ORGANIZATION_OWNER' AND organization_id IS NULL;

-- Three cleanup settings become one. Keep the longest value anyone had set.
INSERT INTO system_properties (organization_id, `key`, value, description)
SELECT 0, 'Cleanup_AuditLog_Days', CAST(MAX(CAST(value AS UNSIGNED)) AS CHAR), 'Days to keep audit history'
FROM system_properties
WHERE organization_id = 0 AND `key` IN ('Cleanup_SystemAudit_Days', 'Cleanup_OfferAudit_Days', 'Cleanup_CouponAudit_Days')
HAVING COUNT(*) > 0;

DELETE FROM system_properties
WHERE organization_id = 0 AND `key` IN ('Cleanup_SystemAudit_Days', 'Cleanup_OfferAudit_Days', 'Cleanup_CouponAudit_Days');

DELETE FROM cleanup_runs WHERE task_key IN ('system_audit', 'offer_audit', 'coupon_audit');
