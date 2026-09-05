DELIMITER //

DROP TRIGGER IF EXISTS trg_user_roles_before_insert //
DROP TRIGGER IF EXISTS trg_user_roles_before_update //

CREATE TRIGGER trg_user_roles_before_insert
BEFORE INSERT ON user_roles
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'COMPANY' THEN
        IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in organizations';
        END IF;
    ELSEIF NEW.scope_type = 'BRANCH_GROUP' THEN
        IF NOT EXISTS (SELECT 1 FROM branch_groups WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in branch_groups';
        END IF;
    ELSEIF NEW.scope_type = 'BRANCH' THEN
        IF NOT EXISTS (SELECT 1 FROM stores WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in stores';
        END IF;
    END IF;
END //

CREATE TRIGGER trg_user_roles_before_update
BEFORE UPDATE ON user_roles
FOR EACH ROW
BEGIN
    IF NEW.scope_type = 'COMPANY' THEN
        IF NOT EXISTS (SELECT 1 FROM organizations WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in organizations';
        END IF;
    ELSEIF NEW.scope_type = 'BRANCH_GROUP' THEN
        IF NOT EXISTS (SELECT 1 FROM branch_groups WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in branch_groups';
        END IF;
    ELSEIF NEW.scope_type = 'BRANCH' THEN
        IF NOT EXISTS (SELECT 1 FROM stores WHERE id = NEW.scope_id) THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'scope_id does not exist in stores';
        END IF;
    END IF;
END //

DELIMITER ;
