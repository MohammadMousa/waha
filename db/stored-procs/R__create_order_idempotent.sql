DELIMITER //

-- id is client-supplied and IS the idempotency key: the Flutter app
-- generates one UUID per checkout attempt (online or offline) and calls
-- this with the same id on any retry or later offline-sync replay. A call
-- with an id that already exists is a safe no-op - it returns
-- was_created=FALSE instead of erroring or double-inserting.
CREATE PROCEDURE sp_create_order_idempotent(
    IN p_id              CHAR(36),
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
