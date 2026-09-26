-- Catalog pull attempts are logged in sync_queue (entity_type = 'CATALOG_PULL').
-- Drop the separate table if it was created by an earlier version of this migration.
DROP TABLE IF EXISTS odoo_sync_log;
