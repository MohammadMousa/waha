-- Runtime-configurable roles: the permissions of each role now live in the database.
-- The list of permissions stays in code. SUPER_ADMIN and KIOSK stay fixed in code and are not seeded.
-- Built-in roles have no organization; custom roles belong to one organization.

ALTER TABLE roles
    ADD COLUMN organization_id BIGINT       NULL AFTER name,
    ADD COLUMN description     VARCHAR(255) NULL AFTER organization_id,
    ADD COLUMN org_key         BIGINT GENERATED ALWAYS AS (IFNULL(organization_id, 0)) STORED,
    DROP INDEX uq_roles_name,
    ADD UNIQUE KEY uq_roles_org_name (org_key, name),
    ADD CONSTRAINT fk_roles_org FOREIGN KEY (organization_id) REFERENCES organizations(id);

CREATE TABLE role_permissions (
    role_id    BIGINT      NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role_id, permission),
    CONSTRAINT fk_rp_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Exactly today's lists, plus the two new role-management permissions for the organization owner.
INSERT INTO role_permissions (role_id, permission)
SELECT r.id, p.permission
FROM roles r
JOIN (
    SELECT 'CASHIER' AS role_name, 'VIEW_PRODUCTS' AS permission
    UNION ALL
    SELECT 'CASHIER' AS role_name, 'VIEW_OWN_ORDERS' AS permission
    UNION ALL
    SELECT 'CASHIER' AS role_name, 'VIEW_ORDER_HISTORY' AS permission
    UNION ALL
    SELECT 'CASHIER' AS role_name, 'VIEW_ALL_ORDERS' AS permission
    UNION ALL
    SELECT 'CASHIER' AS role_name, 'PROCESS_ORDERS' AS permission
    UNION ALL
    SELECT 'CASHIER' AS role_name, 'VIEW_INVENTORY' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_PRODUCTS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'EDIT_PRODUCTS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'MANAGE_CATEGORIES' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_OWN_ORDERS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_ORDER_HISTORY' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_ALL_ORDERS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'PROCESS_ORDERS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'EDIT_RESOURCES' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_INVENTORY' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'PROCESS_INVENTORY' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_OFFERS' AS permission
    UNION ALL
    SELECT 'OPERATOR' AS role_name, 'VIEW_COUPONS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_PRODUCTS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'EDIT_PRODUCTS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'MANAGE_CATEGORIES' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_OWN_ORDERS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_ORDER_HISTORY' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_ALL_ORDERS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'PROCESS_ORDERS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'MANAGE_EMPLOYEES' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'MANAGE_DEVICES' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'MANAGE_STORES' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'EDIT_RESOURCES' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_INVENTORY' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'PROCESS_INVENTORY' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'MANAGE_INVENTORY' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_OFFERS' AS permission
    UNION ALL
    SELECT 'BRANCH_ADMIN' AS role_name, 'VIEW_COUPONS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_PRODUCTS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'EDIT_PRODUCTS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_CATEGORIES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_OWN_ORDERS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_ORDER_HISTORY' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_ALL_ORDERS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'PROCESS_ORDERS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_EMPLOYEES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_DEVICES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_STORES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'EDIT_RESOURCES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_INVENTORY' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'PROCESS_INVENTORY' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_INVENTORY' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_OFFERS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_OFFERS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'VIEW_COUPONS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_COUPONS' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'MANAGE_ROLES' AS permission
    UNION ALL
    SELECT 'ORGANIZATION_OWNER' AS role_name, 'ASSIGN_ROLES' AS permission
) p ON p.role_name = r.name
WHERE r.organization_id IS NULL;
