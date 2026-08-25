-- Dynamic invoice titles per order status. Stored as JSON {"ar":"...","en":"..."}
-- so the renderer can serve the language the customer requested without a separate
-- column per language — adding a 3rd language later is a data change, not a schema change.
ALTER TABLE receipt_info
    ADD COLUMN unpaid_invoice_title JSON NULL AFTER cr_number,
    ADD COLUMN paid_invoice_title   JSON NULL AFTER unpaid_invoice_title;
