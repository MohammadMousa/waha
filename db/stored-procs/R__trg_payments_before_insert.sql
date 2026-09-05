DELIMITER //

DROP TRIGGER IF EXISTS trg_payments_before_insert //

CREATE TRIGGER trg_payments_before_insert
BEFORE INSERT ON payments
FOR EACH ROW
BEGIN
    SELECT o.store_id, s.organization_id
    INTO @p_store_id, @p_org_id
    FROM orders o JOIN stores s ON o.store_id = s.id
    WHERE o.id = NEW.order_id;

    SET NEW.store_id        = @p_store_id;
    SET NEW.organization_id = @p_org_id;
END //

DELIMITER ;
