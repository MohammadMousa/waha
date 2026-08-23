-- Session mode: stored once at login or configureStore, not re-sent on
-- every request. Backend reads it from the session to apply the right
-- validation policy (Normal/Kiosk/Shopping have different rules).
-- properties: generic overflow bag for future session-level config that
-- doesn't deserve its own column yet.
ALTER TABLE user_sessions
    ADD COLUMN mode       ENUM('NORMAL', 'KIOSK', 'SHOPPING') NULL AFTER store_id,
    ADD COLUMN properties JSON                                 NULL AFTER mode;

-- offline_capable: purely informational — returned by GET /api/payment-methods
-- so the frontend can grey out methods that require live connectivity when
-- the device detects it's offline. Backend never enforces this; it can't
-- know if the client is offline.
--
-- REDIRECT  → FALSE: needs internet to open the payment URL
-- TERMINAL  → TRUE:  POS has its own connectivity (SIM/ethernet), kiosk
--                    talks to it locally, works regardless of kiosk network
-- SIMULATED → TRUE:  no network call at all
ALTER TABLE payment_methods
    ADD COLUMN offline_capable BOOLEAN NOT NULL DEFAULT FALSE AFTER available_modes;

-- Update the simulated seed row set in V6.
UPDATE payment_methods SET offline_capable = TRUE WHERE `key` = 'simulated';
-- Any future TERMINAL rows: set offline_capable = TRUE when inserting them.
-- (No TERMINAL rows in seed yet — they're hardware-specific.)
