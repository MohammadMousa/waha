-- Coupons: a code that switches on one COUPON-mode offer. Discount terms stay on the offer.
-- Date-times on coupons are Riyadh local time, like offer dates.

ALTER TABLE offers
    ADD COLUMN activation_mode ENUM('AUTO','COUPON') NOT NULL DEFAULT 'AUTO' AFTER store_scope;

CREATE TABLE coupons (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    organization_id        BIGINT        NOT NULL,
    offer_id               BIGINT        NOT NULL,
    code                   VARCHAR(64)   NOT NULL,
    code_normalized        VARCHAR(64)   NOT NULL,
    enabled                TINYINT(1)    NOT NULL DEFAULT 1,
    starts_at              DATETIME      DEFAULT NULL,
    expires_at             DATETIME      DEFAULT NULL,
    usage_limit            INT           DEFAULT NULL,
    minimum_order_amount   DECIMAL(12,2) DEFAULT NULL,
    max_discount_per_order DECIMAL(12,2) DEFAULT NULL,
    employee_id            BIGINT        DEFAULT NULL,
    deleted_at             DATETIME      DEFAULT NULL,
    created_by             BIGINT        DEFAULT NULL,
    created_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by             BIGINT        DEFAULT NULL,
    updated_at             TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_coupons_org_code (organization_id, code_normalized),
    KEY idx_coupons_offer (offer_id),
    KEY idx_coupons_employee (employee_id),
    CONSTRAINT fk_coupons_org      FOREIGN KEY (organization_id) REFERENCES organizations(id),
    CONSTRAINT fk_coupons_offer    FOREIGN KEY (offer_id)        REFERENCES offers(id),
    CONSTRAINT fk_coupons_employee FOREIGN KEY (employee_id)     REFERENCES employees(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per order that used a coupon. A coupon's usage is the rows whose order is PAID.
CREATE TABLE coupon_redemptions (
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    coupon_id           BIGINT        NOT NULL,
    offer_id            BIGINT        NOT NULL,
    order_id            CHAR(36)      NOT NULL,
    employee_id         BIGINT        DEFAULT NULL,
    code_snapshot       VARCHAR(64)   NOT NULL,
    offer_name_snapshot VARCHAR(120)  NOT NULL,
    discount_amount     DECIMAL(12,2) NOT NULL,
    created_at          TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
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

-- The coupon discount of an order is saved as one invoice-level application, tagged with the coupon.
ALTER TABLE offer_applications
    ADD COLUMN coupon_id   BIGINT      DEFAULT NULL,
    ADD COLUMN coupon_code VARCHAR(64) DEFAULT NULL,
    ADD KEY idx_offer_app_coupon (coupon_id);
