-- Purely additive, no existing table touched.
--
-- Deliberately NOT the SUPER-ADMIN/ADMIN/STORE-MANAGER/CASHIER role system
-- discussed earlier - that's staff/admin RBAC, a separate and still-open
-- question. This is customer identity for Normal Mode (register/login),
-- complementary to orders.username, not a replacement for it - Kiosk still
-- uses its own device-identity pattern (not yet built), and a guest
-- checkout can still just put a phone number/UUID in orders.username
-- without ever creating a user row here.
CREATE TABLE users (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    username       VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uq_users_username (username)
) ENGINE=InnoDB;

-- Opaque bearer token, not a JWT - looked up server-side on every
-- authenticated call, same "unguessable is enough, no crypto framework
-- needed" principle as order ids. store_id is nullable and settable after
-- login (POST /api/auth/store) - the session's "current store" context,
-- not a permanent user attribute.
CREATE TABLE user_sessions (
    token       CHAR(64) PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    store_id    BIGINT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at  TIMESTAMP NOT NULL,
    CONSTRAINT fk_sessions_user  FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_sessions_store FOREIGN KEY (store_id) REFERENCES stores(id)
) ENGINE=InnoDB;
