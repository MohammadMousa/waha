-- Repeatable migration (R__) — must be safely re-runnable whenever this
-- file's content changes, so drop before create rather than assuming a
-- fresh database.
DROP PROCEDURE IF EXISTS sp_create_order_idempotent;

DELIMITER //

-- id is client-supplied and IS the idempotency key: the Flutter app
-- generates one UUID per checkout attempt (online or offline) and calls
-- this with the same id on any retry or later offline-sync replay. A call
-- with an id that already exists is a safe no-op - it returns
-- was_created=FALSE instead of erroring or double-inserting.
-- p_id/p_order_id collation must match orders.id (utf8mb4_0900_ai_ci) explicitly
-- here: without it, MySQL derives the parameter's collation from the session
-- that ran CREATE PROCEDURE (utf8mb4_unicode_ci, this DB's default), and any
-- comparison against orders.id below then fails with "Illegal mix of
-- collations" — see sp_mark_order_paid for the same fix.
CREATE PROCEDURE sp_create_order_idempotent(
    IN p_id              CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    IN p_store_id        BIGINT,
    IN p_currency        CHAR(3),
    IN p_tax_rate        DECIMAL(5,4),
    IN p_username        VARCHAR(255),
    IN p_subtotal_amount DECIMAL(12,2),
    IN p_tax_amount      DECIMAL(12,2),
    OUT p_was_created    BOOLEAN
)
BEGIN
    IF EXISTS (SELECT 1 FROM orders WHERE id = p_id) THEN
        SET p_was_created = FALSE;
    ELSE
        INSERT INTO orders (id, store_id, currency, tax_rate, username, status, subtotal_amount, tax_amount, total_amount)
        VALUES (p_id, p_store_id, p_currency, p_tax_rate, p_username, 'CREATED', p_subtotal_amount, p_tax_amount, p_subtotal_amount + p_tax_amount);

        INSERT INTO order_status_history (id, order_id, status)
        VALUES (UUID(), p_id, 'CREATED');

        SET p_was_created = TRUE;
    END IF;
END //

DELIMITER ;
