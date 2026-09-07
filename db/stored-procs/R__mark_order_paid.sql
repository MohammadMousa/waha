-- Repeatable migration (R__) — must be safely re-runnable whenever this
-- file's content changes, so drop before create rather than assuming a
-- fresh database.
DROP PROCEDURE IF EXISTS sp_mark_order_paid;

DELIMITER //

-- Marks an order PAID exactly once. Optimistic concurrency via `version`
-- guards against a rare double-submit (e.g. a retried /pay call racing
-- itself). An already-PAID or CANCELLED order is left untouched:
-- PAID  → duplicate webhook / retry; nothing to do.
-- CANCELLED → admin cancelled while payment was in flight; reject the PAID
--   transition so the order stays cancelled (see Timeout Policy in specs).
-- p_order_id collation must match orders.id explicitly — see
-- sp_create_order_idempotent for why (same "Illegal mix of collations" bug).
CREATE PROCEDURE sp_mark_order_paid(
    IN p_order_id          CHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci,
    IN p_expected_version  INT,
    IN p_payment_reference VARCHAR(255),
    IN p_sub_status        VARCHAR(50),
    OUT p_success          BOOLEAN
)
BEGIN
    DECLARE current_status VARCHAR(32);
    DECLARE current_version INT;

    SELECT status, version INTO current_status, current_version
    FROM orders
    WHERE id = p_order_id
    FOR UPDATE;

    IF current_version <> p_expected_version OR current_status IN ('PAID', 'CANCELLED') THEN
        SET p_success = FALSE;   -- stale write, already paid, or cancelled
    ELSE
        UPDATE orders
        SET status = 'PAID', payment_reference = p_payment_reference, version = version + 1
        WHERE id = p_order_id;

        INSERT INTO order_status_history (id, order_id, status, sub_status)
        VALUES (UUID(), p_order_id, 'PAID', p_sub_status);

        SET p_success = TRUE;
    END IF;
END //

DELIMITER ;
