DELIMITER //

DROP TRIGGER IF EXISTS trg_employee_stores_before_insert //
DROP TRIGGER IF EXISTS trg_employee_stores_before_update //

CREATE TRIGGER trg_employee_stores_before_insert
BEFORE INSERT ON employee_stores
FOR EACH ROW
BEGIN
    DECLARE emp_org BIGINT;
    DECLARE store_org BIGINT;

    SELECT organization_id INTO emp_org   FROM employees WHERE id = NEW.employee_id;
    SELECT organization_id INTO store_org FROM stores    WHERE id = NEW.store_id;

    IF emp_org IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'employee_id does not exist';
    END IF;
    IF store_org IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store_id does not exist';
    END IF;
    IF emp_org != store_org THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'employee and store belong to different organizations';
    END IF;
END //

CREATE TRIGGER trg_employee_stores_before_update
BEFORE UPDATE ON employee_stores
FOR EACH ROW
BEGIN
    DECLARE emp_org BIGINT;
    DECLARE store_org BIGINT;

    SELECT organization_id INTO emp_org   FROM employees WHERE id = NEW.employee_id;
    SELECT organization_id INTO store_org FROM stores    WHERE id = NEW.store_id;

    IF emp_org IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'employee_id does not exist';
    END IF;
    IF store_org IS NULL THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'store_id does not exist';
    END IF;
    IF emp_org != store_org THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'employee and store belong to different organizations';
    END IF;
END //

DELIMITER ;
