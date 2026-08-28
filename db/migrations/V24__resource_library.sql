-- Named resource library: directories + assets layered on top of the
-- existing content store (resources/resource_data). The content store is
-- content-addressed (sha256 dedup); this layer adds human-readable, store-
-- scoped paths so landing pages and the Resource Explorer can reference
-- assets by name instead of internal id.
--
-- URL scheme: /resource/{store.name}/{directory.name}/{asset.name}

CREATE TABLE resource_directories (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id   BIGINT       NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_res_dir_store FOREIGN KEY (store_id) REFERENCES stores(id),
    UNIQUE KEY uq_res_dir (store_id, name)
) ENGINE=InnoDB;

-- One row per named asset; points to the content in resource_data.
-- The same resource_id can be referenced from multiple paths (cross-store sharing).
CREATE TABLE resource_assets (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    store_id     BIGINT       NOT NULL,
    directory_id BIGINT       NOT NULL,
    name         VARCHAR(255) NOT NULL,
    resource_id  BIGINT       NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_res_asset_store FOREIGN KEY (store_id)     REFERENCES stores(id),
    CONSTRAINT fk_res_asset_dir   FOREIGN KEY (directory_id) REFERENCES resource_directories(id),
    CONSTRAINT fk_res_asset_res   FOREIGN KEY (resource_id)  REFERENCES resources(id),
    UNIQUE KEY uq_res_asset (store_id, directory_id, name)
) ENGINE=InnoDB;

-- System property: max upload size for named assets (bytes). Default 2 MB.
INSERT INTO system_properties (`key`, value)
VALUES ('resource.max_size_bytes', '2097152')
ON DUPLICATE KEY UPDATE value = value;
