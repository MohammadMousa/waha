-- Product categories. Scoped identically to products: scope_store_id NULL
-- means the category is globally available; a non-null value ties it to
-- one store (or grouping node) in the hierarchy. The same scope_key trick
-- from products prevents multiple GLOBAL rows with the same name.
--
-- `public` controls customer visibility: a public category appears in the
-- browse UI; a private one is still useful for admin/internal grouping
-- (e.g. a "Discontinued" bucket) without polluting the customer view.
CREATE TABLE categories (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    scope_store_id   BIGINT NULL,
    name             JSON   NOT NULL,   -- {"ar": "...", "en": "..."}
    public           BOOLEAN NOT NULL DEFAULT TRUE,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order       INT     NOT NULL DEFAULT 0,
    scope_key        BIGINT GENERATED ALWAYS AS (COALESCE(scope_store_id, 0)) STORED,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_categories_scope FOREIGN KEY (scope_store_id) REFERENCES stores(id),
    INDEX idx_categories_scope (scope_key)
) ENGINE=InnoDB;

-- category_id on products: soft reference (no FK constraint) on purpose.
-- A product can be GLOBAL while its category is store-scoped - a FK would
-- force both to share the same scope, which is wrong. The application is
-- responsible for validating that a category_id actually exists and is
-- visible to the requesting store before assigning it.
--
-- `public` on products: controls browse-list visibility.
--   public=TRUE, active=TRUE  -> listed and sellable (normal)
--   public=FALSE, active=TRUE -> scan-only (Kiosk can scan it; won't appear in browse list)
--   public=TRUE, active=FALSE -> listed but not sellable (e.g. out of stock badge)
--   public=FALSE, active=FALSE -> hidden from all customer surfaces
ALTER TABLE products
    ADD COLUMN category_id BIGINT NULL AFTER active,
    ADD COLUMN public       BOOLEAN NOT NULL DEFAULT TRUE AFTER category_id,
    ADD INDEX idx_products_category (category_id);

-- Update browse query to respect public: browsing only shows public products.
-- (The active filter stays where it is - applied after ranking. The public
--  filter is safe to apply before ranking because it's not scope-dependent
--  and doesn't affect which tier wins; it just removes items that should
--  never show in the browse list regardless of scope.)
