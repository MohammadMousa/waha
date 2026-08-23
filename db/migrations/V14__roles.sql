-- Synthetic root store (id=0): represents "system-wide" scope for roles.
-- Using 0 instead of NULL avoids OR store_id IS NULL in every query.
-- store_type/store_kind/currency/vat_rate are required columns; values here
-- are placeholders — this store is never used for orders or products.
INSERT INTO stores (id, store_type, name, display_name, active, public, currency, vat_rate)
VALUES (0, 'PARENT', 'Root', '{"en":"Root","ar":"الجذر"}', 1, 0, 'SAR', 0.15)
ON DUPLICATE KEY UPDATE name = name;

CREATE TABLE IF NOT EXISTS roles (
    id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE
) ENGINE=InnoDB;

INSERT IGNORE INTO roles (name) VALUES
    ('SUPER_ADMIN'),
    ('ADMIN'),
    ('OPERATOR'),
    ('CASHIER'),
    ('REGISTERED'),
    ('ANONYMOUS');

CREATE TABLE IF NOT EXISTS user_roles (
    user_id  BIGINT NOT NULL,
    role_id  BIGINT NOT NULL,
    store_id BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, role_id, store_id),
    CONSTRAINT fk_ur_user  FOREIGN KEY (user_id)  REFERENCES users(id)  ON DELETE CASCADE,
    CONSTRAINT fk_ur_role  FOREIGN KEY (role_id)  REFERENCES roles(id),
    CONSTRAINT fk_ur_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;
