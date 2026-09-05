DELIMITER //

DROP TRIGGER IF EXISTS trg_payment_attempts_before_insert //

CREATE TRIGGER trg_payment_attempts_before_insert
BEFORE INSERT ON payment_attempts
FOR EACH ROW
BEGIN
    SELECT o.store_id, s.organization_id
    INTO @pa_store_id, @pa_org_id
    FROM orders o JOIN stores s ON o.store_id = s.id
    WHERE o.id = NEW.order_id;

    SET NEW.store_id        = @pa_store_id;
    SET NEW.organization_id = @pa_org_id;
END //

DELIMITER ;
