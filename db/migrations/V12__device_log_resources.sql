-- Tag resources uploaded by a device so they can be listed/served/deleted
-- through the admin /api/logs endpoints.  Null = non-device upload (product
-- images, landing pages, etc.) — those stay public via /api/resources/{id}.
ALTER TABLE resources
    ADD COLUMN device_id BIGINT NULL DEFAULT NULL,
    ADD CONSTRAINT fk_resources_device FOREIGN KEY (device_id) REFERENCES devices (id) ON DELETE SET NULL,
    ADD INDEX idx_resources_device (device_id);
