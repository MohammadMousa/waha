-- Widen pin_code to hold BCrypt hashes (60 chars; 72 for headroom)
ALTER TABLE employees MODIFY COLUMN pin_code VARCHAR(72) NOT NULL;
ALTER TABLE devices   MODIFY COLUMN pin_code VARCHAR(72) NOT NULL;

-- Lockout timestamp — written when 5 consecutive failures occur, cleared on success or admin reset.
-- Attempt counter lives in application memory (ConcurrentHashMap) for speed;
-- only the final lockout state is persisted so it survives restarts and is shared across instances.
ALTER TABLE employees ADD COLUMN locked_until TIMESTAMP NULL DEFAULT NULL;
ALTER TABLE devices   ADD COLUMN locked_until TIMESTAMP NULL DEFAULT NULL;
