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

1. Raise `ApiLevel.CURRENT` only when a client app needs the new level to detect the release and choose between the new integration and the old logic. A release with nothing for clients to check keeps the current level.
2. When it is raised, raise it by 1 per deployment, not per change: the level is one above the last deployed backend. Work added before that deployment goes into the same level.
3. When it is raised, add a row to the table below in the same commit.
4. Never lower it, and never remove or change the meaning of an existing endpoint without a new level.
5. Deploying order stays: backend first, then the dashboard / kiosk.

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
| 4 | Custom roles with editable permissions: role and assignment management for dashboard users and employees. See `docs/roles-permissions.md` | admin |
|   | Permissions split out of broader ones: `VIEW_REPORTS`, `EXPORT_REPORTS`, `MANAGE_PAYMENT_METHODS`, `MANAGE_INTEGRATIONS`, `MANAGE_SETTINGS`, `VIEW_INVENTORY_REPORTS`. Roles that held the old permission receive them. Clients re-read permissions when this level changes | admin |
| 5 | Data cleanup: days kept per table as system settings, status of each cleanup task, run now. See `docs/system-configuration.md` | admin |
|   | One shared audit history for system, offers, coupons and roles, with list and delete calls and the `VIEW_ORGANIZATION_AUDIT` permission. See `docs/audit.md`. Cleanup setting `Cleanup_AuditLog_Days` replaces the three audit settings | admin |
|   | Customer profile: first and last name, phone, email, gender, birth date, language. See `docs/customer-profile.md` | client, admin |
|   | Inventory wastes: record, list with totals, export. See `docs/inventory-wastes.md`. Admin product list with search and filters; barcode search in the product lookup | admin |

| 6 | Online payments (Stripe, MyFatoorah) take stock and queue the Odoo push; MyFatoorah "paid" webhooks are confirmed with MyFatoorah itself | admin, kiosk |
|   | Odoo account setting `createMissingProducts`: create a product in Odoo when an order line matches nothing there. Products are found by any barcode. Nightly pull retries after a failure. See `docs/odoo-integration.md` | admin |
|   | Delete unpaid orders: one, many, or older than N days; new permission `DELETE_UNPAID_ORDERS`; daily cleanup task for unpaid orders (off by default). See `docs/unpaid-orders.md` | admin |
| 7 | Combined sync pull: `POST /api/sync/pull` answers device settings, removals and every table in one request, with a row budget; the per-table calls stay. Sync table settings per organization (switch a table off, expiration period). See `docs/sync-combined-pull.md` | kiosk, admin |

## Endpoints per level

### Level 7

- `POST /api/sync/pull` (device session with a store)
- `POST /api/categories` creates a category (permission `MANAGE_CATEGORIES`)
- `GET /api/admin/sync/tables`, `PUT /api/admin/sync/tables/{table}` (permission `MANAGE_SETTINGS`)
- `GET /api/sync/{table}` and `GET /api/sync/removals` answer an empty last page for a table the organization switched off

### Level 6

- `GET /api/admin/orders/unpaid/count`, `DELETE /api/admin/orders/unpaid/{id}`, `POST /api/admin/orders/unpaid/delete`
- `GET /api/admin/odoo/status` returns `createMissingProducts`; `POST /api/admin/odoo/configure` accepts it (omitted keeps the current value)
- Cleanup task `unpaid_orders` and setting `Cleanup_UnpaidOrders_Days` (0 = off, the default)

### Level 5

- `GET /api/admin/system/cleanup`, `POST /api/admin/system/cleanup/run` (super admin)
- Eight new system settings `Cleanup_*_Days` (listed by `GET /api/admin/system/properties`)
- `GET /api/admin/audit`, `DELETE /api/admin/audit/{id}`, `POST /api/admin/audit/delete`, `POST /api/admin/audit/clear`
- Role changes are now recorded in the audit history
- `GET /api/profile`, `PUT /api/profile` (signed-in user)
- `GET /api/admin/inventory/wastes`, `GET /api/admin/inventory/wastes/{id}`, `POST /api/admin/inventory/wastes`, `GET /api/admin/inventory/wastes/reasons`, `GET /api/admin/inventory/wastes/export`
- `GET /api/admin/products` (all products of the organization; `search` by name or barcode, `categoryId`, `active`, `publicListed`, `tag`, `page`, `size`; answer has `totalCount`). Needs `VIEW_PRODUCTS`. `GET /api/products` for apps and kiosks is unchanged
- `GET /api/admin/reports/products?search=` now also matches barcodes and rows carry `barcode`

### Level 4

- `GET /api/admin/permissions`, `GET/POST /api/admin/roles`, `PUT/DELETE /api/admin/roles/{id}`
- `GET/POST /api/admin/users/{id}/roles`, `DELETE /api/admin/users/{id}/roles/{roleId}`; the same under `/api/admin/employees/{id}/roles`

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
