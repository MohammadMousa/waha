# API levels

The backend reports which client-facing features it has in one number, `api_level`.
It is returned by the public config endpoint, `GET /api/config`, together with the other properties.

```json
{ "default_language": "en", "api_level": "3", ... }
```

The value is a string holding a whole number.

## Why it exists

The dashboard (admin) and the kiosk app are released separately from the backend. If a new
dashboard or kiosk goes out before the backend that supports it, the new screens call
endpoints that do not exist yet. `api_level` lets the client check first and hide or skip the feature.

## Rules for clients

1. Read `api_level` from `GET /api/config` at start-up (and again after a reconnect).
2. A missing `api_level` means level 0: a backend from before this scheme. Treat it as 0.
3. Each feature needs a minimum level (table below). If `api_level` is lower, do not call
   the feature's endpoints. Hide the screen or button, or show "backend update pending".
4. If the config call fails, change nothing: keep what the client had, and do not enable new features.
5. A client never breaks on a higher level than it knows. Levels only go up and old endpoints keep working.

## Rules for the backend

1. Raise `ApiLevel.CURRENT` by 1 in any release that adds an endpoint or behaviour a client depends on.
2. Add a row to the table below in the same commit.
3. Never lower it, and never remove or change the meaning of an existing endpoint without a new level.
4. Deploying order stays: backend first, then the dashboard / kiosk.

## Levels

| Level | Adds | Used by |
|------:|------|---------|
| 0 | Everything released before levels existed (no `api_level` field) | all |
| 1 | Device status report (online / offline, last seen, app version, last order) | admin |
|   | Device sessions: list, disconnect one, disconnect selected | admin |
|   | Kiosk heartbeat and app version at login and token check | kiosk |
|   | Late card approval for an order the kiosk gave up on | kiosk |
|   | Organization settings: `heartbeat_minutes`, `late_approval_expiration_minutes`, `enable_logging`, `device_auto_restart_*`, `terminal_timeout_seconds` | admin, kiosk |
|   | Orders and product sales reports accept a page size up to 500 | admin |
| 2 | Offers (auto-applied promotions): admin offer management, discounts in cart quotes, orders and invoices, active offers for kiosk badges. See `docs/offers.md` | admin, kiosk |
| 3 | Account sessions: list, disconnect one or several, sign out of the others, for dashboard users and employees; OS / model / browser of each session; cap on sessions per account. See `docs/account-sessions.md` | admin, client, kiosk |
|   | System Configuration for super-admin: global settings, cache actions, login lockouts and the system audit. See `docs/system-configuration.md` | admin |
|   | Super-admin works inside an organization with the `X-Organization-Id` header; organization list returns slug, search and paging | admin |
|   | Orders and quotes with a zero or negative total are refused (422, `ORDER_TOTAL_NOT_POSITIVE`) | kiosk, client |

## Endpoints per level

### Level 3

- `GET/PUT /api/admin/system/properties`, `GET /api/admin/system/caches`, `POST /api/admin/system/caches/{name}/clear`,
  `POST /api/admin/system/caches/{name}/save`, `POST /api/admin/system/caches/clear-all`, `POST /api/admin/system/caches/save-all`,
  `POST /api/admin/system/lockouts/clear` (MANAGE_SYSTEM only)
- Header `X-Organization-Id` for super-admin; `GET /api/organizations` returns `slug` and takes `search`, `page`, `size`
- `GET /api/admin/system/audit`, `DELETE /api/admin/system/audit/{id}`, `POST /api/admin/system/audit/clear` (MANAGE_SYSTEM only)
- `GET /api/admin/users/{id}/sessions`, `DELETE /api/admin/users/{id}/sessions/{ref}`, `POST /api/admin/users/{id}/sessions/delete`
- `GET /api/admin/employees/{id}/sessions`, `DELETE /api/admin/employees/{id}/sessions/{ref}`, `POST /api/admin/employees/{id}/sessions/delete`
- `GET /api/auth/me/sessions`, `DELETE /api/auth/me/sessions/{ref}`, `POST /api/auth/me/sessions/sign-out-others`
- `appVersion` in the user and employee login bodies, `X-App-Version` header on `GET /api/auth/me` and `GET /api/pos/auth/me`.
- Device session rows also return `info`. Login bodies accept `sessionProperties` with `os`, `osVersion`, `model`, `browser`.
- `POST /api/orders/quote` and `POST /api/orders` answer 422 `ORDER_TOTAL_NOT_POSITIVE` when the total is zero or less.

### Level 2

- `GET /api/admin/offers`, `GET /api/admin/offers/{id}`, `POST /api/admin/offers`, `PUT /api/admin/offers/{id}`,
  `PATCH /api/admin/offers/{id}/enabled`, `DELETE /api/admin/offers/{id}`, `POST /api/admin/offers/preview`
- `GET /api/stores/{storeId}/active-offers`
- Cart quote, order creation and order reads also return discount fields (`discountAmount`, `netAmount`, `taxAmount`,
  `offer` per line; `discountAmount`, `invoiceOffer` per order). Extra fields only: old clients keep working.

### Level 1

- `GET  /api/admin/device-status`
- `GET  /api/admin/devices/{id}/sessions`
- `DELETE /api/admin/devices/{id}/sessions/{ref}`
- `POST /api/admin/devices/{id}/sessions/delete`
- `POST /api/kiosk/heartbeat`
- `POST /api/orders/{orderId}/late-approval`
- `appVersion` in the kiosk login body, `X-App-Version` header on `GET /api/kiosk/auth/me`
