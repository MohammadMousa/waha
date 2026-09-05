-- Seed data for a fresh install.
-- All inserts use INSERT IGNORE so this is safe to run against a restored DB.

-- ── Roles ─────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO roles (id, name) VALUES
    (1, 'SUPER_ADMIN'),
    (2, 'ADMIN'),
    (3, 'OPERATOR'),
    (4, 'CASHIER'),
    (5, 'REGISTERED'),
    (6, 'ANONYMOUS');

-- ── Payment methods ───────────────────────────────────────────────────────────
INSERT IGNORE INTO payment_methods (id, `key`, display_name, provider, available_modes, offline_capable, active, sort_order) VALUES
    (2, 'stripe',       '{"ar":"بطاقة ائتمانية (Stripe)","en":"Stripe"}',           'REDIRECT',    'NORMAL,SHOPPING',     0, 1,  1),
    (3, 'myfatoorah',   '{"ar":"ماي فاتورة","en":"MyFatoorah"}',                    'REDIRECT',    'NORMAL,SHOPPING',     0, 1,  2),
    (4, 'stripe_qr',    '{"ar":"بطاقة ائتمانية (Stripe)","en":"Credit / Debit Card"}', 'QR_LINK', 'KIOSK',               0, 1, 10),
    (5, 'myfatoorah_qr','{"ar":"ماي فاتورة","en":"MyFatoorah"}',                    'QR_LINK',     'KIOSK',               0, 1, 11),
    (6, 'mobile_payment','{"ar":"دفع عبر الهاتف","en":"Mobile Payment"}',           'PAYMENT_URL', 'KIOSK',               0, 1, 10),
    (7, 'terminal',     '{"ar":"دفع نقدي","en":"Terminal Payment"}',                'TERMINAL',    'KIOSK',               1, 1, 90),
    (1, 'simulated',    '{"ar":"دفع تجريبي","en":"Simulated Payment"}',             'SIMULATED',   'NORMAL,KIOSK,SHOPPING',1, 1, 99);

-- ── System properties ─────────────────────────────────────────────────────────
INSERT IGNORE INTO system_properties (`key`, value, description) VALUES
    ('appName',               '{"en":"Waha","ar":"واحة"}',  NULL),
    ('CentralProductSystem',  'true',                        'products creation & import are allowed only in root'),
    ('default_store_id',      '5',                           'Fallback store returned with every auth response (login/register/guest/me)'),
    ('publicBaseUrl',         '',                            'Public base URL for invoice links and payment callbacks (overrides WAHA_PUBLIC_BASE_URL env var; empty = use env var)'),
    ('resource.max_size_bytes','2097152',                    NULL);

-- ── Organization ──────────────────────────────────────────────────────────────
INSERT IGNORE INTO organizations (id, name) VALUES (1, 'Demo Retail Group');

-- ── Branch group ──────────────────────────────────────────────────────────────
INSERT IGNORE INTO branch_groups (id, organization_id, name) VALUES (2, 1, 'delta');

-- ── Stores ────────────────────────────────────────────────────────────────────
INSERT IGNORE INTO stores (id, organization_id, branch_group_id, name, display_name, active, public, currency, vat_rate) VALUES
    (1, 1, NULL, 'waha',  '{"ar":"واحة","en":"Waha"}',         1, 0, 'EGP', 0.1400),
    (5, 1,    2, 'alj',   '{"ar":"الجزيرة","en":"Aljazeera"}', 1, 1, 'EGP', 0.1400),
    (6, 1,    2, 'epc',   '{"ar":"بنك ايبك","en":"EPC Bank"}', 1, 1, 'EGP', 0.1400),
    (7, 1, NULL, 'oasis', '{"ar":"واحتي","en":"My Oasis"}',    1, 1, 'SAR', 0.1500);

-- ── Categories ────────────────────────────────────────────────────────────────
INSERT IGNORE INTO categories (id, company_id, name) VALUES
    (1, 1, '{"ar":"مشروبات","en":"Beverages"}'),
    (2, 1, '{"ar":"وجبات خفيفة","en":"Snacks"}'),
    (3, 1, '{"ar":"طعام","en":"Food"}'),
    (4, 1, '{"ar":"نظافة شخصية","en":"Hygiene"}');

-- ── Demo products ─────────────────────────────────────────────────────────────
INSERT IGNORE INTO products (id, company_id, barcode, name, price, active) VALUES
    (1, 1, '6221031000015', '{"ar":"مياه معبأة 600مل","en":"Bottled Water 600ml"}',       8.00, 1),
    (2, 1, '6221031000022', '{"ar":"علبة كولا 330مل","en":"Cola Can 330ml"}',            15.00, 1),
    (3, 1, '6221031000039', '{"ar":"شيبس بطاطس 40ج","en":"Potato Chips 40g"}',           20.00, 1),
    (4, 1, '6221031000046', '{"ar":"لوح شوكولاتة 45ج","en":"Chocolate Bar 45g"}',        25.00, 1),
    (5, 1, '6221031000053', '{"ar":"كوب نودلز سريع التحضير","en":"Instant Noodles Cup"}', 18.00, 1),
    (6, 1, '6221031000060', '{"ar":"عبوة مناديل للسفر","en":"Tissue Pack Travel Size"}',  12.00, 1),
    (7, 1, '6221031000077', '{"ar":"منتج نموذجي متوقف","en":"Discontinued Sample Item"}', 10.00, 0);
