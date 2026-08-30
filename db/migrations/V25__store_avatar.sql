-- Store avatar: optional primary image for display in store picker and store edit screens.
ALTER TABLE stores
    ADD COLUMN image_resource_id BIGINT NULL,
    ADD CONSTRAINT fk_stores_image FOREIGN KEY (image_resource_id) REFERENCES resources(id);
