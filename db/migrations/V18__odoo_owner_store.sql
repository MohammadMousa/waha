-- V18: Track which store owns an Odoo integration.
-- Owner = the store that created the integration and performs catalog pulls.
-- Children in the same hierarchy inherit the integration for order push only.
ALTER TABLE external_systems
    ADD COLUMN owner_store_id BIGINT NULL REFERENCES stores(id) AFTER customer_override;

-- Existing ODOO integration belongs to the root store (id=1).
UPDATE external_systems SET owner_store_id = 1 WHERE name = 'ODOO';
