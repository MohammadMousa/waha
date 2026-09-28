ALTER TABLE organizations
    ADD COLUMN vat_rate  DECIMAL(5,4) NULL AFTER slug,
    ADD COLUMN currency  CHAR(3)      NULL AFTER vat_rate;

-- Seed org-level values from the first store in each org
UPDATE organizations o
    JOIN stores s ON s.organization_id = o.id
SET o.currency = s.currency,
    o.vat_rate = s.vat_rate
WHERE o.currency IS NULL;
