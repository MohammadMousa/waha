-- Inventory domain: stock levels, visits, operations, items.

-- ── 1. store_inventory: running stock level per (store, product) ──────────────
CREATE TABLE IF NOT EXISTS store_inventory (
    store_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (store_id, product_id),
    CONSTRAINT fk_si_store   FOREIGN KEY (store_id)   REFERENCES stores(id),
    CONSTRAINT fk_si_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 2. inventory_visits: one record per employee visit/activity session ───────
CREATE TABLE IF NOT EXISTS inventory_visits (
    id              BIGINT    NOT NULL AUTO_INCREMENT,
    organization_id BIGINT    NOT NULL,
    employee_id     BIGINT    NOT NULL,
    store_id        BIGINT    NOT NULL,
    status          ENUM('ACTIVE','COMPLETED','CANCELLED') NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP NULL DEFAULT NULL,
    PRIMARY KEY (id),
    INDEX idx_iv_employee (employee_id),
    INDEX idx_iv_store (store_id),
    INDEX idx_iv_org_status (organization_id, status),
    CONSTRAINT fk_iv_org      FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_iv_employee FOREIGN KEY (employee_id)     REFERENCES employees(id),
    CONSTRAINT fk_iv_store    FOREIGN KEY (store_id)        REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 3. inventory_operations: TRANSFER or RETURN records within a visit ────────
CREATE TABLE IF NOT EXISTS inventory_operations (
    id              BIGINT NOT NULL AUTO_INCREMENT,
    visit_id        BIGINT NOT NULL,
    operation_type  ENUM('TRANSFER','RETURN') NOT NULL,
    target_store_id BIGINT NOT NULL,
    notes           TEXT   DEFAULT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_io_visit (visit_id),
    CONSTRAINT fk_io_visit        FOREIGN KEY (visit_id)        REFERENCES inventory_visits(id),
    CONSTRAINT fk_io_target_store FOREIGN KEY (target_store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ── 4. inventory_operation_items: products + quantities per operation ─────────
CREATE TABLE IF NOT EXISTS inventory_operation_items (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    operation_id BIGINT NOT NULL,
    product_id   BIGINT NOT NULL,
    quantity     INT    NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_op_product (operation_id, product_id),
    CONSTRAINT fk_ioi_operation FOREIGN KEY (operation_id) REFERENCES inventory_operations(id) ON DELETE CASCADE,
    CONSTRAINT fk_ioi_product   FOREIGN KEY (product_id)   REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
