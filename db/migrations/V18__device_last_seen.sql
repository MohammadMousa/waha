-- Device management: last signal and app version, kept per session and per device.
ALTER TABLE user_sessions
    ADD COLUMN last_seen_at DATETIME     DEFAULT NULL,
    ADD COLUMN app_version  VARCHAR(50)  DEFAULT NULL;

ALTER TABLE devices
    ADD COLUMN last_seen_at DATETIME     DEFAULT NULL,
    ADD COLUMN app_version  VARCHAR(50)  DEFAULT NULL;
