-- Adds an optional deployment-level Odoo customer name override to external_systems.
-- When set, all orders from this deployment use the named Odoo partner instead of
-- the identity-aware resolution (DEVICE/GUEST/USER).
ALTER TABLE external_systems
    ADD COLUMN customer_override VARCHAR(255) NULL AFTER username;
