-- Daily data cleanup: remembers when each cleanup task last ran and what it removed.
-- The number of days kept per task lives in system_properties (organization 0).

CREATE TABLE cleanup_runs (
    task_key     VARCHAR(50)  NOT NULL,
    last_run_at  DATETIME     NOT NULL,
    last_removed BIGINT       NOT NULL DEFAULT 0,
    last_source  VARCHAR(12)  NOT NULL DEFAULT 'SCHEDULED',
    last_error   VARCHAR(255) NULL,
    PRIMARY KEY (task_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
