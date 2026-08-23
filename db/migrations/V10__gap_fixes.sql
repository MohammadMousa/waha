-- GAP 1: Product name → bilingual JSON ({"ar":"...","en":"..."})
-- Match every other multilingual field in the system (stores.display_name,
-- categories.name, payment_methods.display_name).
-- Migration wraps existing English-only names as {"en": "<old value>"}.
ALTER TABLE products ADD COLUMN name_new JSON NULL;
UPDATE products SET name_new = JSON_OBJECT('en', name);
ALTER TABLE products DROP COLUMN name;
ALTER TABLE products RENAME COLUMN name_new TO name;
-- Restore NOT NULL now that all rows are populated.
ALTER TABLE products MODIFY COLUMN name JSON NOT NULL;

-- GAP 2: Product description (bilingual, optional)
ALTER TABLE products ADD COLUMN description JSON NULL AFTER name;

-- GAP 3: Category image
ALTER TABLE categories
    ADD COLUMN image_resource_id BIGINT NULL,
    ADD CONSTRAINT fk_categories_image FOREIGN KEY (image_resource_id) REFERENCES resources(id);

-- GAP 6: Order history — index on username so user order history is fast.
ALTER TABLE orders ADD INDEX idx_orders_username_store (username, store_id, created_at DESC);
