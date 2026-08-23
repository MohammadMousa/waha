-- ── Category key ─────────────────────────────────────────────────────────────
-- Slug/identifier used to deduplicate categories pulled from Odoo.
-- Unique per scope (scope_key = COALESCE(scope_store_id, 0)).
ALTER TABLE categories
    ADD COLUMN `key` VARCHAR(100) NULL AFTER name,
    ADD UNIQUE KEY uq_categories_scope_key (scope_key, `key`);

-- ── external_systems ──────────────────────────────────────────────────────────
-- One row per external system connected to Waha (e.g. ODOO).
-- api_key is stored here; at rest it is plaintext — encrypt at the infra
-- level (KMS / secrets manager) if the threat model requires it.
CREATE TABLE external_systems (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(50)  NOT NULL UNIQUE,
    base_url                VARCHAR(500),
    api_key                 VARCHAR(500),
    enabled                 BOOLEAN      NOT NULL DEFAULT TRUE,
    last_category_sync_at   TIMESTAMP    NULL,
    last_product_sync_at    TIMESTAMP    NULL,
    created_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB;

-- ── external_mappings ─────────────────────────────────────────────────────────
-- Bidirectional map: Waha local_id <-> external system's own identifier.
-- local_id is VARCHAR(50) to accommodate both UUID-style (orders) and
-- numeric-string (product/category BIGINT) local identifiers.
-- store_id is nullable: category/product mappings carry it for multi-branch
-- clients; system-wide mappings leave it NULL.
CREATE TABLE external_mappings (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_id     BIGINT       NOT NULL,
    entity_type   VARCHAR(50)  NOT NULL,
    local_id      VARCHAR(50)  NOT NULL,
    external_id   VARCHAR(100) NOT NULL,
    store_id      BIGINT       NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_em_local    (system_id, entity_type, local_id),
    UNIQUE KEY uq_em_external (system_id, entity_type, external_id),
    CONSTRAINT fk_em_system FOREIGN KEY (system_id) REFERENCES external_systems(id)
) ENGINE=InnoDB;

-- ── sync_queue ────────────────────────────────────────────────────────────────
-- Outbound async work: order/payment pushes to external systems.
-- Waha never blocks a sale on Odoo availability — items land here after
-- payment and are processed by a background worker with retry logic.
CREATE TABLE sync_queue (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_id     BIGINT       NOT NULL,
    entity_type   VARCHAR(50)  NOT NULL,
    entity_id     VARCHAR(50)  NOT NULL,
    operation     VARCHAR(20)  NOT NULL,
    payload       JSON         NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    TEXT,
    store_id      BIGINT       NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_sq_system FOREIGN KEY (system_id) REFERENCES external_systems(id),
    INDEX idx_sq_status_created (status, created_at),
    INDEX idx_sq_entity (entity_type, entity_id)
) ENGINE=InnoDB;
