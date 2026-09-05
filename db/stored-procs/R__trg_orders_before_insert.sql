DELIMITER //

DROP TRIGGER IF EXISTS trg_orders_before_insert //

CREATE TRIGGER trg_orders_before_insert
BEFORE INSERT ON orders
FOR EACH ROW
BEGIN
    SET NEW.organization_id = (SELECT organization_id FROM stores WHERE id = NEW.store_id);
END //

DELIMITER ;
