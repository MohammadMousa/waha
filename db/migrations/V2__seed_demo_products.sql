-- Demo hierarchy, 4 levels deep on purpose - this is what actually proves
-- arbitrary-depth resolution works, not just that the columns exist.
--
-- WAHA (root)
-- └── Demo Retail Group / REAL (org-level grouping)
--     └── City 1 (city-level grouping)
--         ├── City 1 Warehouse (CHILD, WAREHOUSE, operator-only)
--         ├── Branch 1 (CHILD, RETAIL, public)
--         └── Branch 2 (CHILD, RETAIL, public)

INSERT INTO organizations (id, name) VALUES
    (1, 'Demo Retail Group');

INSERT INTO addresses (id, building_number, street_name, district, city, postal_code, country_code) VALUES
    (1, '1234', 'Demo Street', 'Downtown', 'Cairo', '11511', 'EG');

INSERT INTO stores (id, organization_id, parent_store_id, path, store_type, store_kind, name, display_name, active, public, address_id, currency, vat_rate) VALUES
    -- Platform root - not owned by any single organization, never a checkout point.
    (1, NULL, NULL, NULL,    'PARENT', NULL,        'waha',                  JSON_OBJECT('ar', 'واحة',                'en', 'Waha'),           TRUE, FALSE, NULL, 'EGP', 0.1400),
    -- Company-level grouping under the root.
    (2, 1,    1,    '1',     'PARENT', NULL,        'real',                  JSON_OBJECT('ar', 'الحقيقي',             'en', 'Real'),           TRUE, FALSE, NULL, 'EGP', 0.1400),
    -- City-level grouping under the company.
    (3, 1,    2,    '1/2',   'PARENT', NULL,        'real-city1',            JSON_OBJECT('ar', 'المدينة الأولى',      'en', 'City 1'),         TRUE, FALSE, NULL, 'EGP', 0.1400),
    -- Operator-only warehouse - never public, per definition (product setup,
    -- purchase invoices, transfers - not a customer-facing location).
    (4, 1,    3,    '1/2/3', 'CHILD',  'WAREHOUSE', 'real-city1-warehouse',  JSON_OBJECT('ar', 'مستودع المدينة الأولى', 'en', 'City 1 Warehouse'), TRUE, FALSE, NULL, 'EGP', 0.1400),
    -- Real operating branches - the actual testable storeId values.
    (5, 1,    3,    '1/2/3', 'CHILD',  'RETAIL',    'real-city1-branch1',    JSON_OBJECT('ar', 'الفرع الأول',         'en', 'Branch 1'),        TRUE, TRUE,  1,    'EGP', 0.1400),
    (6, 1,    3,    '1/2/3', 'CHILD',  'RETAIL',    'real-city1-branch2',    JSON_OBJECT('ar', 'الفرع الثاني',        'en', 'Branch 2'),        TRUE, TRUE,  NULL, 'EGP', 0.1400);

-- Tags: decorative only, for admin eyeballing - not a query mechanism
-- (structural queries go through path). #Repo marks warehouses across the
-- whole system; #pilot marks these two branches as part of the same rollout
-- cohort, independent of the tree structure that already relates them.
INSERT INTO store_tags (store_id, tag) VALUES
    (4, '#Repo'),
    (5, '#pilot'),
    (6, '#pilot');

-- Baseline catalog - GLOBAL (scope_store_id NULL), available everywhere by
-- default.
INSERT INTO products (barcode, name, price, active, scope_store_id, scope_store_type) VALUES
    ('6221031000015', 'Bottled Water 600ml',       8.00,  TRUE,  NULL, NULL),
    ('6221031000022', 'Cola Can 330ml',            15.00, TRUE,  NULL, NULL),
    ('6221031000039', 'Potato Chips 40g',           20.00, TRUE,  NULL, NULL),
    ('6221031000046', 'Chocolate Bar 45g',          25.00, TRUE,  NULL, NULL),
    ('6221031000053', 'Instant Noodles Cup',         18.00, TRUE,  NULL, NULL),
    ('6221031000060', 'Tissue Pack Travel Size',      12.00, TRUE,  NULL, NULL),
    ('6221031000077', 'Discontinued Sample Item',     10.00, FALSE, NULL, NULL);  -- exercises the "not sellable" scan response

-- Three overrides at three different depths, deliberately on three
-- different products, so each can be tested independently:

-- Company-wide (2 levels above Branch 1/2) - "real" (id 2). Both branches
-- inherit this, since neither has its own override for this barcode.
INSERT INTO products (barcode, name, price, active, scope_store_id, scope_store_type) VALUES
    ('6221031000039', 'Potato Chips 40g', 18.00, TRUE, 2, 'PARENT');

-- City-wide (1 level above Branch 1/2) - "real-city1" (id 3). Also inherited
-- by both branches - proves a *mid*-tier override works, not just the
-- immediate parent or the top of the chain.
INSERT INTO products (barcode, name, price, active, scope_store_id, scope_store_type) VALUES
    ('6221031000053', 'Instant Noodles Cup', 16.00, TRUE, 3, 'PARENT');

-- Branch-specific (Branch 1 only, id 5). Branch 2 is unaffected and still
-- resolves this barcode to the GLOBAL price - proves sibling branches don't
-- leak each other's overrides even though they share every ancestor above
-- them.
INSERT INTO products (barcode, name, price, active, scope_store_id, scope_store_type) VALUES
    ('6221031000022', 'Cola Can 330ml', 13.00, TRUE, 5, 'CHILD');
