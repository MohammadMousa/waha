ALTER TABLE external_systems
    ADD COLUMN push_target VARCHAR(10) NOT NULL DEFAULT 'SALES' AFTER customer_override;

-- Branch -> Odoo point of sale, and Waha payment method -> Odoo payment method.
-- Separate from external_mappings because several branches or payment methods
-- may point to the same Odoo record, which external_mappings' unique keys forbid.
CREATE TABLE odoo_pos_links (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    system_id   BIGINT       NOT NULL,
    link_type   VARCHAR(20)  NOT NULL,
    local_key   VARCHAR(100) NOT NULL,
    odoo_id     BIGINT       NOT NULL,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uq_opl (system_id, link_type, local_key),
    CONSTRAINT fk_opl_system FOREIGN KEY (system_id) REFERENCES external_systems(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
