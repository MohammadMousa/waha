-- Coupons: standalone codes with their own discount rule and limits (they do not link to offers).
-- Date-times on coupons are Riyadh local time, like offer dates. Every limit that is NULL means unlimited.
CREATE TABLE coupons (
    id                         BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id            BIGINT        NOT NULL,
    code                       VARCHAR(64)   NOT NULL,
    code_normalized            VARCHAR(64)   NOT NULL,
    name                       VARCHAR(120)  DEFAULT NULL,
    type                       ENUM('GLOBAL','PRODUCT')    NOT NULL,
    discount_mode              ENUM('AMOUNT','PERCENTAGE') NOT NULL,
    discount_value             DECIMAL(12,2) NOT NULL,
    max_discount_per_order     DECIMAL(12,2) DEFAULT NULL,
    store_scope                ENUM('ALL','SOME') NOT NULL DEFAULT 'ALL',
    enabled                    TINYINT(1)    NOT NULL DEFAULT 1,
    starts_at                  DATETIME      DEFAULT NULL,
    expires_at                 DATETIME      DEFAULT NULL,
    max_invoices               INT           DEFAULT NULL,
    max_invoices_per_user      INT           DEFAULT NULL,
    max_amount                 DECIMAL(12,2) DEFAULT NULL,
    max_amount_per_user        DECIMAL(12,2) DEFAULT NULL,
    minimum_order_amount       DECIMAL(12,2) DEFAULT NULL,
    allow_stacking_with_offers TINYINT(1)    NOT NULL DEFAULT 1,
    employee_id                BIGINT        DEFAULT NULL,
    deleted_at                 DATETIME      DEFAULT NULL,
    created_by                 BIGINT        DEFAULT NULL,
    created_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by                 BIGINT        DEFAULT NULL,
    updated_at                 TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupons_org_code (organization_id, code_normalized),
    KEY idx_coupons_employee (employee_id),
    CONSTRAINT fk_coupons_org      FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_coupons_employee FOREIGN KEY (employee_id)     REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_stores (
    coupon_id BIGINT NOT NULL,
    store_id  BIGINT NOT NULL,
    PRIMARY KEY (coupon_id, store_id),
    CONSTRAINT fk_coupon_stores_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    CONSTRAINT fk_coupon_stores_store  FOREIGN KEY (store_id)  REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_products (
    coupon_id  BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (coupon_id, product_id),
    KEY idx_coupon_products_product (product_id),
    CONSTRAINT fk_coupon_products_coupon  FOREIGN KEY (coupon_id)  REFERENCES coupons(id),
    CONSTRAINT fk_coupon_products_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per order that used a coupon. A coupon's usage is the rows whose order is PAID.
CREATE TABLE coupon_redemptions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    coupon_id       BIGINT        NOT NULL,
    order_id        CHAR(36)      NOT NULL,
    employee_id     BIGINT        DEFAULT NULL,
    code_snapshot   VARCHAR(64)   NOT NULL,
    name_snapshot   VARCHAR(120)  DEFAULT NULL,
    discount_amount DECIMAL(12,2) NOT NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupon_redemption_order (coupon_id, order_id),
    KEY idx_coupon_redemption_order (order_id),
    KEY idx_coupon_redemption_coupon (coupon_id, created_at),
    KEY idx_coupon_redemption_employee (employee_id, created_at),
    CONSTRAINT fk_coupon_redemption_coupon FOREIGN KEY (coupon_id) REFERENCES coupons(id),
    CONSTRAINT fk_coupon_redemption_order  FOREIGN KEY (order_id)  REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE coupon_audit (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    coupon_id   BIGINT      NOT NULL,
    user_id     BIGINT      DEFAULT NULL,
    action      VARCHAR(20) NOT NULL,
    before_json JSON        DEFAULT NULL,
    after_json  JSON        DEFAULT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_coupon_audit_coupon (coupon_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
