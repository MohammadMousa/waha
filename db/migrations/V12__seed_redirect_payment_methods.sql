-- Seed real REDIRECT payment methods for Normal and Shopping modes.
-- Stripe and MyFatoorah are both redirect-based (backend creates a session
-- URL, frontend opens it externally, backend receives webhook → marks PAID).
-- Kiosk always uses SIMULATED (no browser redirect on a locked terminal).
INSERT INTO payment_methods (`key`, display_name, provider, available_modes, offline_capable, active, sort_order)
VALUES
    ('stripe',      '{"ar": "بطاقة ائتمانية (Stripe)",  "en": "Credit / Debit Card"}', 'REDIRECT', 'NORMAL,SHOPPING', FALSE, TRUE, 1),
    ('myfatoorah',  '{"ar": "ماي فاتورة",               "en": "MyFatoorah"}',           'REDIRECT', 'NORMAL,SHOPPING', FALSE, TRUE, 2);
