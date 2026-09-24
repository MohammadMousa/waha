-- Report/dashboard query performance indexes.
-- All queries filter on status+created_at, often also store_id and total_amount.
CREATE INDEX idx_orders_reports
    ON orders (status, created_at, store_id, total_amount);

-- order_items is joined on order_id and product_id in every products-sales query.
CREATE INDEX idx_order_items_reports
    ON order_items (order_id, product_id, quantity, unit_price);

-- payments is probed per-order for payment type in the orders report.
CREATE INDEX idx_payments_order_outcome
    ON payments (order_id, outcome, provider);
