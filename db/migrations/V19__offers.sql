-- Offers: auto-applied promotions (see docs/offers.md).
CREATE TABLE offers (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id     BIGINT        NOT NULL,
    name                VARCHAR(120)  NOT NULL,
    type                ENUM('GLOBAL','PRODUCT')        NOT NULL,
    discount_mode       ENUM('AMOUNT','PERCENTAGE')     NOT NULL,
    discount_value      DECIMAL(12,2) NOT NULL,
    max_discount_amount DECIMAL(12,2) DEFAULT NULL,
    start_date          DATE          NOT NULL,
    end_date            DATE          NOT NULL,
    enabled             TINYINT(1)    NOT NULL DEFAULT 1,
    store_scope         ENUM('ALL','SOME')              NOT NULL DEFAULT 'ALL',
    deleted_at          DATETIME      DEFAULT NULL,
    created_by          BIGINT        DEFAULT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by          BIGINT        DEFAULT NULL,
    updated_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_offers_active (organization_id, enabled, start_date, end_date),
    CONSTRAINT fk_offers_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE offer_stores (
    offer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    PRIMARY KEY (offer_id, store_id),
    CONSTRAINT fk_offer_stores_offer FOREIGN KEY (offer_id) REFERENCES offers(id),
    CONSTRAINT fk_offer_stores_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE offer_products (
    offer_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    PRIMARY KEY (offer_id, product_id),
    KEY idx_offer_products_product (product_id),
    CONSTRAINT fk_offer_products_offer   FOREIGN KEY (offer_id)   REFERENCES offers(id),
    CONSTRAINT fk_offer_products_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per applied offer per order; terms are copied so later edits never change history.
CREATE TABLE offer_applications (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    order_id          CHAR(36)      NOT NULL,
    order_item_id     BIGINT        DEFAULT NULL,
    offer_id          BIGINT        NOT NULL,
    offer_name        VARCHAR(120)  NOT NULL,
    offer_type        VARCHAR(20)   NOT NULL,
    discount_mode     VARCHAR(20)   NOT NULL,
    discount_value    DECIMAL(12,2) NOT NULL,
    discount_amount   DECIMAL(12,2) NOT NULL,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_offer_app_order (order_id),
    KEY idx_offer_app_offer (offer_id, created_at),
    CONSTRAINT fk_offer_app_order FOREIGN KEY (order_id) REFERENCES orders(id),
    CONSTRAINT fk_offer_app_offer FOREIGN KEY (offer_id) REFERENCES offers(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Every admin change with the user and the before/after values.
CREATE TABLE offer_audit (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    offer_id   BIGINT      NOT NULL,
    user_id    BIGINT      DEFAULT NULL,
    action     VARCHAR(20) NOT NULL,
    before_json JSON       DEFAULT NULL,
    after_json  JSON       DEFAULT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_offer_audit_offer (offer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

ALTER TABLE orders
    ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00;

ALTER TABLE order_items
    ADD COLUMN discount_amount DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    ADD COLUMN tax_amount      DECIMAL(12,2) DEFAULT NULL;
