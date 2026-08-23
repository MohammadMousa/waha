-- Global payment method catalog. Each row represents one payment channel
-- (e.g. MyFatoorah, Stripe, Simulated) that may or may not be enabled for
-- a given store. The `key` is a stable machine identifier (e.g. "myfatoorah",
-- "simulated") used by the backend to route to the right provider.
--
-- provider:
--   REDIRECT  - sends the customer to an external URL (shown as QR in Kiosk mode)
--   TERMINAL  - card-present device, no URL involved
--   SIMULATED - dev/test mode; always succeeds
--
-- available_modes: which app modes can use this method. A TERMINAL method
-- shouldn't be offered on a web-only NORMAL flow; a REDIRECT method is
-- useless on a TERMINAL-only kiosk that has no browser, etc.
CREATE TABLE payment_methods (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    `key`            VARCHAR(50)  NOT NULL UNIQUE,
    display_name     JSON         NOT NULL,  -- {"ar": "...", "en": "..."}
    provider         ENUM('REDIRECT', 'TERMINAL', 'SIMULATED') NOT NULL,
    available_modes  SET('NORMAL', 'KIOSK', 'SHOPPING') NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,  -- global kill switch
    sort_order       INT          NOT NULL DEFAULT 0
) ENGINE=InnoDB;

-- Per-store override table. A store can opt individual methods in or out
-- without touching the global record, and can override sort order locally.
-- Absence of a row = method inherits its global state (available if
-- payment_methods.active = TRUE). Presence with active = FALSE = method is
-- disabled for this store even if globally active.
CREATE TABLE payment_methods_store (
    id                BIGINT  AUTO_INCREMENT PRIMARY KEY,
    store_id          BIGINT  NOT NULL,
    payment_method_id BIGINT  NOT NULL,
    active            BOOLEAN NOT NULL DEFAULT TRUE,  -- per-branch override
    sort_order        INT     NULL,  -- NULL = fall back to payment_methods.sort_order
    UNIQUE KEY uq_store_method (store_id, payment_method_id),
    CONSTRAINT fk_pms_store  FOREIGN KEY (store_id)          REFERENCES stores(id),
    CONSTRAINT fk_pms_method FOREIGN KEY (payment_method_id) REFERENCES payment_methods(id)
) ENGINE=InnoDB;

-- Seed: simulated method works out of the box for dev/testing (all modes).
-- Add real REDIRECT/TERMINAL rows here or via admin API as providers are onboarded.
INSERT INTO payment_methods (`key`, display_name, provider, available_modes, active, sort_order) VALUES
    ('simulated', '{"ar": "دفع تجريبي", "en": "Simulated Payment"}', 'SIMULATED', 'NORMAL,KIOSK,SHOPPING', TRUE, 99);
