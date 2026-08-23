-- Phase 2: rename payment_attempts → payments, UUID v7 PKs, PENDING/CANCELLED statuses

-- 1. Expand orders.status enum before history mirrors it
ALTER TABLE orders MODIFY COLUMN status ENUM('CREATED', 'PENDING', 'PAID', 'CANCELLED') NOT NULL;

-- 2. Rename table: payment_attempts → payments
RENAME TABLE payment_attempts TO payments;

-- 3. Swap payments PK from BIGINT AUTO_INCREMENT → CHAR(36) UUID
--    No other table FKs payments.id so this is safe.
ALTER TABLE payments ADD COLUMN new_id CHAR(36) NULL FIRST;
UPDATE payments SET new_id = UUID();
ALTER TABLE payments MODIFY COLUMN new_id CHAR(36) NOT NULL;
-- Must remove AUTO_INCREMENT from id before DROP PRIMARY KEY (MySQL requirement)
ALTER TABLE payments MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE payments DROP PRIMARY KEY, ADD PRIMARY KEY (new_id);
ALTER TABLE payments DROP COLUMN id;
ALTER TABLE payments RENAME COLUMN new_id TO id;

-- 4. Add provider column (stripe / myfatoorah / terminal / simulated)
ALTER TABLE payments ADD COLUMN provider VARCHAR(50) NULL AFTER order_id;

-- 5. Expand order_status_history.status enum
ALTER TABLE order_status_history MODIFY COLUMN status ENUM('CREATED', 'PENDING', 'PAID', 'CANCELLED') NOT NULL;

-- 6. Swap order_status_history PK from BIGINT AUTO_INCREMENT → CHAR(36) UUID
ALTER TABLE order_status_history ADD COLUMN new_id CHAR(36) NULL FIRST;
UPDATE order_status_history SET new_id = UUID();
ALTER TABLE order_status_history MODIFY COLUMN new_id CHAR(36) NOT NULL;
-- Must remove AUTO_INCREMENT from id before DROP PRIMARY KEY (MySQL requirement)
ALTER TABLE order_status_history MODIFY COLUMN id BIGINT NOT NULL;
ALTER TABLE order_status_history DROP PRIMARY KEY, ADD PRIMARY KEY (new_id);
ALTER TABLE order_status_history DROP COLUMN id;
ALTER TABLE order_status_history RENAME COLUMN new_id TO id;

-- 7. Add sub_status column (records payment channel at each transition)
ALTER TABLE order_status_history ADD COLUMN sub_status VARCHAR(50) NULL AFTER status;
