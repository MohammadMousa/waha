-- Generic file storage. resources holds metadata; resource_data holds the
-- actual bytes in a separate table so metadata queries never drag blobs.
-- SHA-256 hash enables deduplication: uploading the same file twice returns
-- the existing resource_id without re-storing the bytes.
CREATE TABLE resources (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename    VARCHAR(255) NOT NULL,
    mime_type   VARCHAR(100) NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    sha256      CHAR(64)     NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_resources_sha256 (sha256)
) ENGINE=InnoDB;

CREATE TABLE resource_data (
    resource_id  BIGINT    NOT NULL PRIMARY KEY,
    data         LONGBLOB  NOT NULL,
    CONSTRAINT fk_resource_data FOREIGN KEY (resource_id) REFERENCES resources(id)
) ENGINE=InnoDB;

-- Product avatar: single primary image, nullable.
ALTER TABLE products
    ADD COLUMN image_resource_id BIGINT NULL AFTER category_id,
    ADD CONSTRAINT fk_products_image FOREIGN KEY (image_resource_id) REFERENCES resources(id);

-- Product gallery: optional extra images, ordered. Separate from the avatar
-- so the avatar lookup (browse list, cart) never needs a JOIN or subquery.
-- sort_order controls display sequence; the avatar (image_resource_id) is
-- always position 0 conceptually and is NOT duplicated here.
CREATE TABLE product_images (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    product_id   BIGINT NOT NULL,
    resource_id  BIGINT NOT NULL,
    sort_order   INT    NOT NULL DEFAULT 0,
    CONSTRAINT fk_product_images_product  FOREIGN KEY (product_id)  REFERENCES products(id),
    CONSTRAINT fk_product_images_resource FOREIGN KEY (resource_id) REFERENCES resources(id),
    INDEX idx_product_images_product (product_id)
) ENGINE=InnoDB;

-- receipt_info.logo_resource_id was added as a nullable column in V8
-- with no FK (Phase C placeholder). Wire the FK now.
ALTER TABLE receipt_info
    ADD CONSTRAINT fk_receipt_logo FOREIGN KEY (logo_resource_id) REFERENCES resources(id);
