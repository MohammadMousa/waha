-- Customer profile: personal details kept apart from the login account in users.
-- One row per user, created when the person first saves a profile.
CREATE TABLE user_profiles (
    user_id           BIGINT       NOT NULL,
    organization_id   BIGINT       NOT NULL,
    first_name        VARCHAR(100) DEFAULT NULL,
    last_name         VARCHAR(100) DEFAULT NULL,
    phone             VARCHAR(20)  DEFAULT NULL,
    phone_verified_at TIMESTAMP    NULL DEFAULT NULL,
    email             VARCHAR(255) DEFAULT NULL,
    gender            VARCHAR(10)  DEFAULT NULL,
    birth_date        DATE         DEFAULT NULL,
    language          VARCHAR(5)   DEFAULT NULL,
    avatar_resource_id BIGINT      DEFAULT NULL,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id),
    UNIQUE KEY uq_profile_phone (organization_id, phone),
    UNIQUE KEY uq_profile_email (organization_id, email),
    CONSTRAINT fk_profile_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_profile_avatar FOREIGN KEY (avatar_resource_id) REFERENCES resources(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
