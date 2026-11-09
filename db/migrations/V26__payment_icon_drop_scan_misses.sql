-- Optional icon for a payment method (a resource id); apps use their own fixed icon when it is empty.
ALTER TABLE payment_methods
    ADD COLUMN icon_resource_id BIGINT DEFAULT NULL,
    ADD CONSTRAINT fk_pm_icon FOREIGN KEY (icon_resource_id) REFERENCES resources(id) ON DELETE SET NULL;

-- Old one-row-per-scan table; V21 already copied it into product_missing and nothing writes it any more.
DROP TABLE product_scan_misses;
