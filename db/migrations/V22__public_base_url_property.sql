-- Stores the public-facing base URL used for invoice links, PDF hrefs, and
-- payment-gateway success/cancel URLs. When non-empty, overrides the
-- WAHA_PUBLIC_BASE_URL environment variable at runtime without requiring
-- a container restart. Empty string = keep using the env var fallback.
INSERT IGNORE INTO system_properties (`key`, value, description)
VALUES ('publicBaseUrl', '', 'Public base URL for invoice links and payment callbacks (overrides WAHA_PUBLIC_BASE_URL env var; empty = use env var)');
