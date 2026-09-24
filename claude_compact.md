# Claude Compact Reference

Copy the content of the latest entry into `/compact keep: ...` when starting a new session.

---

## 2026-09-23 — Auth Security + Infra

```
/compact keep: auth security implementation complete — V9 migration (pin_code widened to VARCHAR(72), locked_until added to employees+devices), BCryptPasswordEncoder @Bean (SecurityConfig.java), PinLockoutService (hybrid memory+DB, 5 failures → 15-min lockout, clears on success/admin reset), PinHashingRunner (hashes plain-text PINs on startup after migration), PATCH /api/auth/me/password (verifies current password, min 8 chars new), PIN validation changed 4→6 digits in EmployeeAdminController + DeviceAdminController, PINs BCrypt-hashed before storing on create/patch, lockout cleared on admin PIN reset or re-enable, lockedUntil field added to EmployeeAdminView + DeviceAdminView responses and returned in list/detail API — to_Frontend_AI_On_Auth_Task written (password change UX: current password gates form, no plain passwords in local storage, 6-digit write-only PIN, lockedUntil badge, admin unlocks via PIN reset or re-enable) — to_Kiosk_AI_On_Auth_Task written (accept 6-digit PIN input, no auto-retry on 401) — container rebuild still pending: run docker compose build --no-cache waha && docker compose up -d waha — EC2 needs new container deployed — docker-compose.prod.yml + nginx-admin.conf created for Nginx production setup on EC2 — log rotation added to docker-compose.yml (waha: 20m×5, mysql: 10m×3) — prior open: waha-admin barcodes UI still pending (to_Frontend_AI_On_Barcodes_Task)
```
