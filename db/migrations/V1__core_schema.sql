-- Aurora MySQL 8.0 compatible. CHECK constraints require MySQL 8.0.16+
-- (enforced, not just parsed) - the mysql:8.0 image is well past that.

-- Legal/billing entity (relevant for e.g. ZATCA e-invoicing later) -
-- deliberately separate from `stores`, which models physical/operational
-- kiosk locations plus virtual grouping nodes that anchor the hierarchy.
-- Conflating the two would mean every store row carries fields that are
-- meaningless half the time depending on whether it's a real location or
-- an org standing in as one.
CREATE TABLE organizations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ZATCA-shaped address block, as a starting point - not claiming full
-- compliance-completeness yet, just the reference table and the FK wired
-- up so it doesn't need retrofitting later. Fields can be filled in further
-- once there's a real requirement driving the exact list.
CREATE TABLE addresses (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_number   VARCHAR(10) NULL,
    street_name       VARCHAR(255) NULL,
    secondary_number  VARCHAR(10) NULL,
    district          VARCHAR(255) NULL,
    city              VARCHAR(255) NULL,
    postal_code       VARCHAR(10) NULL,
    country_code      CHAR(2) NULL,   -- ISO 3166-1 alpha-2 (e.g. 'SA', 'EG')
    additional_info   VARCHAR(255) NULL,
    created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- Arbitrary-depth hierarchy (not fixed at 2 tiers - real client structures
-- go 4-5 levels deep: platform root -> company -> region/city -> branch).
--
-- store_type is NOT "root vs non-root" - it's "organizational grouping node
-- (PARENT) vs real operational location where transactions happen (CHILD)",
-- and a PARENT can itself have a PARENT (a city grouping under a company
-- grouping under the platform root). Position in the tree is fully carried
-- by parent_store_id / path; store_type is orthogonal to depth.
--
-- store_kind further splits CHILD (operational) stores into RETAIL vs
-- WAREHOUSE - a different axis again (what kind of activity happens here),
-- meaningless for a PARENT grouping node, hence NULL there.
--
-- path holds ONLY the ancestor chain, root-to-immediate-parent, slash
-- separated, NEVER including the store's own id (e.g. a store under
-- Root -> Company -> City has path = "1/4"). It is backend-owned: never
-- accept a client-supplied path, never let the frontend compute it. On
-- create, path = parent.path + "/" + parent.id (or NULL if parent.path is
-- NULL, i.e. the parent is the tree root). On move (parent_store_id
-- change), every descendant's path must be rebuilt too, not just the
-- moved store's own.
CREATE TABLE stores (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    organization_id  BIGINT NULL,
    parent_store_id  BIGINT NULL,
    path             VARCHAR(500) NULL,
    store_type       ENUM('PARENT', 'CHILD') NOT NULL,
    store_kind       ENUM('RETAIL', 'WAREHOUSE') NULL,
    name             VARCHAR(255) NOT NULL,
    -- {"ar": "...", "en": "..."} - the backend never resolves/translates
    -- this, always returns the whole blob as-is. Locale selection and
    -- re-render on language switch happen entirely client-side, with zero
    -- extra server calls.
    display_name     JSON NULL,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    -- Customer-facing vs operator-only. Warehouses (product setup, purchase
    -- invoices, transfers) and pure organizational grouping nodes are never
    -- public - there's nothing for a customer to interact with there.
    public           BOOLEAN NOT NULL DEFAULT FALSE,
    address_id       BIGINT NULL,
    currency         CHAR(3) NOT NULL,
    vat_rate         DECIMAL(5,4) NOT NULL,
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_stores_org     FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_stores_parent  FOREIGN KEY (parent_store_id) REFERENCES stores(id),
    CONSTRAINT fk_stores_address FOREIGN KEY (address_id) REFERENCES addresses(id),
    -- Globally unique, not per-organization - a future vanity/custom URL
    -- resolving directly to a store by name only works if the name can't
    -- collide across unrelated organizations.
    UNIQUE KEY uq_stores_name (name),
    INDEX idx_stores_path (path),
    -- parent_store_id and path are NULL together (true tree root) or set
    -- together - never one without the other.
    CONSTRAINT chk_stores_root CHECK (
        (parent_store_id IS NULL AND path IS NULL) OR
        (parent_store_id IS NOT NULL AND path IS NOT NULL)
    ),
    -- store_kind is meaningful only for an operational (CHILD) location.
    CONSTRAINT chk_stores_kind CHECK (
        (store_type = 'PARENT' AND store_kind IS NULL) OR
        (store_type = 'CHILD' AND store_kind IS NOT NULL)
    )
) ENGINE=InnoDB;

-- Decorative/organizational labels for admin eyeballing (#test, #closed,
-- #companyX) - deliberately NOT a reporting/query mechanism. Structural
-- queries ("all branches under company X") go through path; tags are just
-- how a human visually classifies stores at a glance. One row per tag, not
-- a packed string - a store can carry several.
CREATE TABLE store_tags (
    store_id  BIGINT NOT NULL,
    tag       VARCHAR(50) NOT NULL,
    PRIMARY KEY (store_id, tag),
    CONSTRAINT fk_store_tags_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;

-- Scope is expressed by which store a row belongs to, not a separate
-- overrides table: the SAME barcode can exist as multiple rows at
-- different scope levels, and resolution picks whichever is most specific
-- for the requesting store, walking the FULL ancestor chain now (not just
-- one parent level) - see ProductRepository.resolveByBarcode.
-- scope_store_id NULL = GLOBAL (available everywhere, no store owns it).
-- No organization_id here on purpose - product scope is expressed entirely
-- in terms of stores; an org's "ownership" of a product is really just
-- that product being scoped to the org's top-level grouping store, no
-- separate concept needed.
CREATE TABLE products (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    barcode             VARCHAR(64) NOT NULL,
    name                VARCHAR(255) NOT NULL,
    price               DECIMAL(12,2) NOT NULL,
    active              BOOLEAN NOT NULL DEFAULT TRUE,
    scope_store_id      BIGINT NULL,
    scope_store_type    ENUM('PARENT', 'CHILD') NULL,
    -- MySQL's UNIQUE index treats every NULL as distinct from every other
    -- NULL, so a plain UNIQUE(barcode, scope_store_id) would silently allow
    -- multiple GLOBAL rows for the same barcode - the one thing this
    -- constraint most needs to prevent. This generated column collapses
    -- NULL to 0 (never a real auto-increment value) so the uniqueness check
    -- actually applies to the GLOBAL tier too.
    scope_key           BIGINT GENERATED ALWAYS AS (COALESCE(scope_store_id, 0)) STORED,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_products_scope_store FOREIGN KEY (scope_store_id) REFERENCES stores(id),
    UNIQUE KEY uq_products_barcode_scope (barcode, scope_key),
    -- Only guards "one is null, the other isn't" - it can't verify
    -- scope_store_type actually matches the referenced store's real type
    -- (MySQL CHECK constraints can't reference other tables). That match is
    -- the application's job: scope_store_type must always be derived and
    -- stamped from the resolved store, never accepted as raw input.
    CONSTRAINT chk_products_scope_consistency CHECK (
        (scope_store_id IS NULL AND scope_store_type IS NULL) OR
        (scope_store_id IS NOT NULL AND scope_store_type IS NOT NULL)
    )
) ENGINE=InnoDB;

-- id is a client-generated UUID (v4), not an auto-increment integer. This
-- single field does three jobs at once:
--   1. Idempotency key - retrying "create" with the same id is a no-op
--      instead of a duplicate order.
--   2. Offline-safe identity - the Flutter app mints this the moment the
--      customer taps "Checkout," online or offline, and it stays the order's
--      real, final id through a later sync. No local-id-to-server-id
--      reconciliation needed when Phase 4 (offline-first) arrives.
--   3. Access credential - a UUID v4 isn't guessable, so knowing one order's
--      id gives no way to enumerate others. No separate access token needed.
CREATE TABLE orders (
    id                 CHAR(36) PRIMARY KEY,
    store_id           BIGINT NOT NULL,
    status             ENUM('CREATED', 'PAID') NOT NULL,
    currency           CHAR(3) NOT NULL,          -- snapshotted from stores.currency at creation time
    tax_rate           DECIMAL(5,4) NOT NULL,     -- snapshotted from stores.vat_rate at creation time - stays accurate even if the store's rate changes later
    -- Required, mandatory for ZATCA. Deliberately a plain string, not a
    -- typed actor/FK - kiosk-account, webapp-account, and guest (phone
    -- number or an auto-generated UUID) identifiers all land here as
    -- whatever value the caller has, and which pattern it is gets read back
    -- out of the string itself rather than being encoded as a separate
    -- column. If a real users/kiosk-accounts system gets built later, this
    -- becomes a natural migration target, not a redesign.
    username           VARCHAR(255) NOT NULL,
    subtotal_amount    DECIMAL(12,2) NOT NULL,
    tax_amount         DECIMAL(12,2) NOT NULL,
    total_amount       DECIMAL(12,2) NOT NULL,
    payment_reference  VARCHAR(255) NULL,
    version            INT NOT NULL DEFAULT 0,    -- optimistic concurrency guard
    created_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;

CREATE TABLE order_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    CHAR(36) NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT NOT NULL,
    unit_price  DECIMAL(12,2) NOT NULL,
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id),
    INDEX idx_order_items_order_id (order_id)
) ENGINE=InnoDB;

-- Audit trail: every real header-status transition (CREATED -> PAID), never
-- overwritten.
CREATE TABLE order_status_history (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id    CHAR(36) NOT NULL,
    status      ENUM('CREATED', 'PAID') NOT NULL,
    changed_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_status_history_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_status_history_order_id (order_id)
) ENGINE=InnoDB;

-- Separate from order_status_history on purpose: logs every /pay call,
-- including simulated declines, which never change the order's header
-- status.
CREATE TABLE payment_attempts (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id            CHAR(36) NOT NULL,
    outcome             ENUM('PENDING', 'PAID', 'FAILED') NOT NULL,
    provider_reference  VARCHAR(255) NULL,
    detail              VARCHAR(255) NULL,
    attempted_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_payment_attempts_order FOREIGN KEY (order_id) REFERENCES orders(id),
    INDEX idx_payment_attempts_order_id (order_id)
) ENGINE=InnoDB;
