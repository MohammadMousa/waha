-- Tokens stay valid until logout or revocation (Waha Rules: Auto-login), for every kind of login.
-- TIMESTAMP cannot hold dates after 2038, so the expiry becomes DATETIME.
ALTER TABLE user_sessions MODIFY expires_at DATETIME NOT NULL;

UPDATE user_sessions SET expires_at = '9999-12-31 00:00:00' WHERE expires_at > NOW();
