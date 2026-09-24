-- Covers date-range and ORDER BY created_at queries on orders when no status filter is applied.
-- InnoDB appends the PK (id), so this also serves ORDER BY created_at DESC, id DESC.
CREATE INDEX idx_orders_created ON orders (created_at);
