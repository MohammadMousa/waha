-- Rows that were really deleted on the server (see docs/offline.md, "Removals"). Devices read this list
-- to delete their local copies. A nightly job removes old rows and remembers how far it cleaned.
CREATE TABLE sync_removals (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    organization_id BIGINT      NOT NULL,
    table_name      VARCHAR(40) NOT NULL,
    row_id          BIGINT      NOT NULL,
    removed_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_sync_removals_org (organization_id, id),
    KEY idx_sync_removals_time (removed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE sync_removals_state (
    id              TINYINT NOT NULL,
    purged_up_to_id BIGINT  NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO sync_removals_state (id, purged_up_to_id) VALUES (1, 0);
