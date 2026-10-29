-- Super-admin audit: critical system actions only (setting changes, cache actions, lockouts cleared, audit deletes).
CREATE TABLE system_audit (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id BIGINT       DEFAULT NULL,
    actor_name    VARCHAR(255) DEFAULT NULL,
    action        VARCHAR(50)  NOT NULL,
    target        VARCHAR(255) DEFAULT NULL,
    old_value     TEXT         DEFAULT NULL,
    new_value     TEXT         DEFAULT NULL,
    details       VARCHAR(500) DEFAULT NULL,
    ip            VARCHAR(64)  DEFAULT NULL,
    PRIMARY KEY (id),
    KEY idx_system_audit_created (created_at),
    KEY idx_system_audit_action (action, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
