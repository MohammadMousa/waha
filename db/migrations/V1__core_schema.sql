-- Complete schema — Company → Branch Group → Store hierarchy.
-- All tables in FK-dependency order.

CREATE TABLE addresses (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    building_number   VARCHAR(10)  DEFAULT NULL,
    street_name       VARCHAR(255) DEFAULT NULL,
    secondary_number  VARCHAR(10)  DEFAULT NULL,
    district          VARCHAR(255) DEFAULT NULL,
    city              VARCHAR(255) DEFAULT NULL,
    postal_code       VARCHAR(10)  DEFAULT NULL,
    country_code      CHAR(2)      DEFAULT NULL,
    additional_info   VARCHAR(255) DEFAULT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE organizations (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    name       VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resources (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    filename    VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(100) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_resources_sha256 (sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE roles (
    id   BIGINT      NOT NULL AUTO_INCREMENT,
    name VARCHAR(50) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_roles_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_methods (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    `key`           VARCHAR(50)  NOT NULL,
    display_name    JSON         NOT NULL,
    provider        ENUM('REDIRECT','TERMINAL','SIMULATED','QR_LINK','PAYMENT_URL') NOT NULL,
    available_modes SET('NORMAL','KIOSK','SHOPPING') NOT NULL,
    offline_capable TINYINT(1)   NOT NULL DEFAULT 0,
    active          TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order      INT          NOT NULL DEFAULT 0,
    payment_url     VARCHAR(500) DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_pm_key (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE system_properties (
    `key`       VARCHAR(100) NOT NULL,
    value       VARCHAR(500) NOT NULL,
    description VARCHAR(255) DEFAULT NULL,
    PRIMARY KEY (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_systems (
    id                     BIGINT       NOT NULL AUTO_INCREMENT,
    name                   VARCHAR(50)  NOT NULL,
    base_url               VARCHAR(500) DEFAULT NULL,
    api_key                VARCHAR(500) DEFAULT NULL,
    username               VARCHAR(255) DEFAULT NULL,
    customer_override      VARCHAR(255) DEFAULT NULL,
    owner_organization_id  BIGINT       DEFAULT NULL,
    enabled                TINYINT(1)   NOT NULL DEFAULT 1,
    last_category_sync_at  TIMESTAMP    NULL DEFAULT NULL,
    last_product_sync_at   TIMESTAMP    NULL DEFAULT NULL,
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_es_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE branch_groups (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id BIGINT       NOT NULL,
    name            VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_bg_org FOREIGN KEY (organization_id) REFERENCES organizations(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE stores (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    organization_id  BIGINT       DEFAULT NULL,
    name             VARCHAR(255) NOT NULL,
    display_name     JSON         DEFAULT NULL,
    active           TINYINT(1)   NOT NULL DEFAULT 1,
    public           TINYINT(1)   NOT NULL DEFAULT 0,
    address_id       BIGINT       DEFAULT NULL,
    currency         CHAR(3)      NOT NULL,
    vat_rate         DECIMAL(5,4) NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    image_resource_id BIGINT      DEFAULT NULL,
    branch_group_id  BIGINT       DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_stores_name (name),
    CONSTRAINT fk_stores_org     FOREIGN KEY (organization_id)   REFERENCES organizations(id),
    CONSTRAINT fk_store_bg       FOREIGN KEY (branch_group_id)   REFERENCES branch_groups(id),
    CONSTRAINT fk_stores_address FOREIGN KEY (address_id)        REFERENCES addresses(id),
    CONSTRAINT fk_stores_image   FOREIGN KEY (image_resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_directories (
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    store_id   BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_res_dir (store_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_data (
    resource_id BIGINT    NOT NULL,
    data        LONGBLOB  NOT NULL,
    PRIMARY KEY (resource_id),
    CONSTRAINT fk_resource_data FOREIGN KEY (resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE resource_assets (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    store_id     BIGINT       NOT NULL,
    directory_id BIGINT       NOT NULL,
    name         VARCHAR(255) NOT NULL,
    resource_id  BIGINT       NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_res_asset (store_id, directory_id, name),
    CONSTRAINT fk_res_asset_store FOREIGN KEY (store_id)     REFERENCES stores(id),
    CONSTRAINT fk_res_asset_dir   FOREIGN KEY (directory_id) REFERENCES resource_directories(id),
    CONSTRAINT fk_res_asset_res   FOREIGN KEY (resource_id)  REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE categories (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    name              JSON         NOT NULL,
    `key`             VARCHAR(100) DEFAULT NULL,
    public            TINYINT(1)   NOT NULL DEFAULT 1,
    active            TINYINT(1)   NOT NULL DEFAULT 1,
    sort_order        INT          NOT NULL DEFAULT 0,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    image_resource_id BIGINT       DEFAULT NULL,
    company_id        BIGINT       NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    CONSTRAINT fk_cat_company FOREIGN KEY (company_id)        REFERENCES organizations(id),
    CONSTRAINT fk_cat_image   FOREIGN KEY (image_resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE products (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    barcode           VARCHAR(64)   NOT NULL,
    sku               VARCHAR(50)   DEFAULT NULL,
    price             DECIMAL(12,2) NOT NULL,
    active            TINYINT(1)    NOT NULL DEFAULT 1,
    category_id       BIGINT        DEFAULT NULL,
    image_resource_id BIGINT        DEFAULT NULL,
    public            TINYINT(1)    NOT NULL DEFAULT 1,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    name              JSON          NOT NULL,
    description       JSON          DEFAULT NULL,
    company_id        BIGINT        NOT NULL DEFAULT 1,
    PRIMARY KEY (id),
    UNIQUE KEY uq_products_barcode_company (barcode, company_id),
    KEY idx_products_category (category_id),
    CONSTRAINT fk_prod_company FOREIGN KEY (company_id)        REFERENCES organizations(id),
    CONSTRAINT fk_products_image FOREIGN KEY (image_resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_images (
    id          BIGINT NOT NULL AUTO_INCREMENT,
    product_id  BIGINT NOT NULL,
    resource_id BIGINT NOT NULL,
    sort_order  INT    NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_product_images_product (product_id),
    CONSTRAINT fk_product_images_product  FOREIGN KEY (product_id)  REFERENCES products(id),
    CONSTRAINT fk_product_images_resource FOREIGN KEY (resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_tags (
    product_id BIGINT       NOT NULL,
    tag        VARCHAR(100) NOT NULL,
    PRIMARY KEY (product_id, tag),
    CONSTRAINT fk_product_tags_product FOREIGN KEY (product_id) REFERENCES products(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE product_scan_misses (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    barcode    VARCHAR(64) NOT NULL,
    store_id   BIGINT      NOT NULL,
    scanned_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scan_misses_barcode (barcode),
    CONSTRAINT fk_scan_misses_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE users (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    username      VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    account_type  VARCHAR(10)  NOT NULL DEFAULT 'HUMAN',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    first_name    VARCHAR(100) DEFAULT NULL,
    last_name     VARCHAR(100) DEFAULT NULL,
    phone         VARCHAR(30)  DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_users_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_roles (
    user_id    BIGINT NOT NULL,
    role_id    BIGINT NOT NULL,
    scope_id   BIGINT NOT NULL DEFAULT 0,
    scope_type ENUM('COMPANY','BRANCH_GROUP','BRANCH') NOT NULL DEFAULT 'BRANCH',
    PRIMARY KEY (user_id, role_id, scope_id),
    KEY idx_ur_scope (scope_id),
    CONSTRAINT fk_ur_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_ur_role FOREIGN KEY (role_id) REFERENCES roles(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_sessions (
    token      CHAR(64)  NOT NULL,
    user_id    BIGINT    NOT NULL,
    store_id   BIGINT    DEFAULT NULL,
    mode       ENUM('NORMAL','KIOSK','SHOPPING') DEFAULT NULL,
    properties JSON      DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    PRIMARY KEY (token),
    CONSTRAINT fk_sessions_user  FOREIGN KEY (user_id)  REFERENCES users(id),
    CONSTRAINT fk_sessions_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE orders (
    id                CHAR(36)      NOT NULL,
    display_id        BIGINT        DEFAULT NULL,
    store_id          BIGINT        NOT NULL,
    status            ENUM('CREATED','PENDING','PAID','CANCELLED') NOT NULL,
    currency          CHAR(3)       NOT NULL,
    tax_rate          DECIMAL(5,4)  NOT NULL,
    username          VARCHAR(255)  NOT NULL,
    subtotal_amount   DECIMAL(12,2) NOT NULL,
    tax_amount        DECIMAL(12,2) NOT NULL,
    total_amount      DECIMAL(12,2) NOT NULL,
    payment_reference VARCHAR(255)  DEFAULT NULL,
    version           INT           NOT NULL DEFAULT 0,
    created_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_orders_display_id (store_id, display_id),
    KEY idx_orders_username_store (username, store_id, created_at DESC),
    CONSTRAINT fk_orders_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_items (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    order_id   CHAR(36)      NOT NULL,
    product_id BIGINT        NOT NULL,
    quantity   INT           NOT NULL,
    unit_price DECIMAL(12,2) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order_id (order_id),
    CONSTRAINT fk_order_items_order   FOREIGN KEY (order_id)   REFERENCES orders(id),
    CONSTRAINT fk_order_items_product FOREIGN KEY (product_id) REFERENCES products(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE order_status_history (
    id         CHAR(36)  NOT NULL,
    order_id   CHAR(36)  NOT NULL,
    status     ENUM('CREATED','PENDING','PAID','CANCELLED') NOT NULL,
    sub_status VARCHAR(50) DEFAULT NULL,
    changed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_status_history_order_id (order_id),
    CONSTRAINT fk_status_history_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE store_order_sequences (
    store_id     BIGINT NOT NULL,
    `last_value` BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (store_id),
    CONSTRAINT fk_seq_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE store_tags (
    store_id BIGINT      NOT NULL,
    tag      VARCHAR(50) NOT NULL,
    PRIMARY KEY (store_id, tag),
    CONSTRAINT fk_store_tags_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_methods_store (
    id                BIGINT    NOT NULL AUTO_INCREMENT,
    store_id          BIGINT    NOT NULL,
    payment_method_id BIGINT    NOT NULL,
    active            TINYINT(1) NOT NULL DEFAULT 1,
    sort_order        INT       DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_store_method (store_id, payment_method_id),
    CONSTRAINT fk_pms_store  FOREIGN KEY (store_id)          REFERENCES stores(id),
    CONSTRAINT fk_pms_method FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payments (
    id                 CHAR(36)     NOT NULL,
    order_id           CHAR(36)     NOT NULL,
    provider           VARCHAR(50)  DEFAULT NULL,
    outcome            ENUM('PENDING','PAID','FAILED') NOT NULL,
    provider_reference VARCHAR(255) DEFAULT NULL,
    detail             VARCHAR(255) DEFAULT NULL,
    attempted_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_payment_attempts_order_id (order_id),
    CONSTRAINT fk_payment_attempts_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_attempts (
    id                 CHAR(36)   NOT NULL,
    order_id           CHAR(36)   NOT NULL,
    provider           VARCHAR(50)  NOT NULL,
    provider_reference VARCHAR(255) NOT NULL,
    redirect_url       VARCHAR(500) NOT NULL DEFAULT '',
    qr_data_uri        MEDIUMTEXT   DEFAULT NULL,
    expires_at         DATETIME     NOT NULL,
    created_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status             VARCHAR(20)  DEFAULT NULL,
    auth_code          VARCHAR(20)  DEFAULT NULL,
    notes              JSON         DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_pa_order (order_id),
    KEY idx_pa_ref   (provider_reference),
    CONSTRAINT fk_pa_order FOREIGN KEY (order_id) REFERENCES orders(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE receipt_info (
    store_id             BIGINT       NOT NULL,
    name_ar              VARCHAR(255) NOT NULL,
    name_en              VARCHAR(255) NOT NULL,
    address_text         TEXT         DEFAULT NULL,
    vat_number           VARCHAR(50)  DEFAULT NULL,
    cr_number            VARCHAR(50)  DEFAULT NULL,
    unpaid_invoice_title JSON         DEFAULT NULL,
    paid_invoice_title   JSON         DEFAULT NULL,
    logo_resource_id     BIGINT       DEFAULT NULL,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (store_id),
    CONSTRAINT fk_receipt_info_store FOREIGN KEY (store_id)         REFERENCES stores(id),
    CONSTRAINT fk_receipt_logo       FOREIGN KEY (logo_resource_id) REFERENCES resources(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_queue (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    system_id   BIGINT      NOT NULL,
    entity_type VARCHAR(50) NOT NULL,
    entity_id   VARCHAR(50) NOT NULL,
    operation   VARCHAR(20) NOT NULL,
    payload     JSON        NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts    INT         NOT NULL DEFAULT 0,
    last_error  TEXT        DEFAULT NULL,
    store_id    BIGINT      DEFAULT NULL,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sq_status_created (status, created_at),
    KEY idx_sq_entity (entity_type, entity_id),
    CONSTRAINT fk_sq_system FOREIGN KEY (system_id) REFERENCES external_systems(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE external_mappings (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    system_id   BIGINT       NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    local_id    VARCHAR(50)  NOT NULL,
    external_id VARCHAR(100) NOT NULL,
    store_id    BIGINT       DEFAULT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_em_local    (system_id, entity_type, local_id),
    UNIQUE KEY uq_em_external (system_id, entity_type, external_id),
    CONSTRAINT fk_em_system FOREIGN KEY (system_id) REFERENCES external_systems(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
