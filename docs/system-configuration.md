# System configuration (super-admin)

System-level settings, cache actions, login lockouts and the system audit. Super-admin only
(`MANAGE_SYSTEM`); everyone else gets 403. Available from `api_level` 3.
Organization-level settings stay in `/api/admin/organization/properties` and are not part of this screen.

A super-admin is stored with organization 0. Logging in without an `organizationId` (or with 0) finds that user.

## Settings (global, organization 0)

| Call | Result |
|------|--------|
| `GET /api/admin/system/properties` | `{ "items": [ { key, value, default, type, description, constraints } ], "others": [ { key, value } ] }` |
| `PUT /api/admin/system/properties` body `{ "key": "value", ... }` | the same list after saving; 400 with a message on an unknown key or a bad value |

- `value` is null when the row does not exist yet (the default applies).
- `others` are other global rows (defaults for organization settings), shown read-only.
- Changes take effect at once, without a restart. Changing `publicBaseUrl` also refreshes its cache.
- Each changed value is written to the system audit (old and new value).
- Every global setting is visible in the public `GET /api/config`. Do not store secrets as system settings.

| Key | Type | Default | Constraints |
|-----|------|---------|-------------|
| `publicBaseUrl` | url | empty (server environment value) | `http://` or `https://`, no spaces; a trailing slash is removed; empty is allowed |
| `Account_Sessions_Cap` | integer | 5 | 0-1000; 0 turns the cap off |
| `last_seen_flush_minutes` | integer | 60 | 1-1440 |
| `CentralProductSystem` | boolean | true | true or false |
| `resource.max_size_bytes` | integer | 2097152 | 1024-104857600 |

## Caches

| Call | Result |
|------|--------|
| `GET /api/admin/system/caches` | `{ "items": [ { name, description, entries, loadedAt, canClear, canSave } ] }` |
| `POST /api/admin/system/caches/{name}/clear` | `{ "cleared": true }`; 404 unknown name, 400 if it cannot be cleared |
| `POST /api/admin/system/caches/{name}/save` | `{ "saved": n }`; only `last-seen` can be saved |
| `POST /api/admin/system/caches/clear-all` | `{ "cleared": n }` ("Flush cache"; does not touch `last-seen`) |
| `POST /api/admin/system/caches/save-all` | `{ "saved": n }` ("Save cache") |

| name | What | clear | save |
|------|------|-------|------|
| `public-base-url` | base URL read from settings | read again on next use | - |
| `offers` | active offers per store (60 s) | reload on next use | - |
| `last-seen` | last-seen times and app versions not yet in the database | - | write now |
| `odoo-partner` | Odoo customer lookup | forget it | - |

Clearing a cache is safe: data is read again from the database. "Save" writes pending last-seen data now instead of at the next
hourly flush. See `docs/last-seen-and-request-load.md`.

## Login lockouts

`POST /api/admin/system/lockouts/clear` lifts every lockout (users, employees, devices, unknown names, addresses).
Reply `{ "cleared": n }`. It is deliberately not part of "Flush cache".

## System audit

The system audit is the `SYSTEM` part of the shared audit history (see `docs/audit.md`); the three calls below keep working and only show and change system rows. Only critical actions are written, so it stays small:

| action | target | When |
|--------|--------|------|
| `SETTING_CHANGED` | setting key | a system setting changed (old and new value) |
| `CACHE_CLEARED` | cache name or `all` | cache cleared |
| `CACHE_SAVED` | `last-seen` or `all` | pending last-seen data saved |
| `LOCKOUTS_CLEARED` | - | login lockouts cleared |
| `AUDIT_ROW_DELETED` | row id | one audit row deleted (old call) |
| `AUDIT_ROWS_DELETED` | what was deleted | audit rows deleted through the audit history calls |
| `AUDIT_CLEARED` | `all` or `before <date>` | audit cleared (this one row is kept) |

Row: `{ id, createdAt, actorUserId, actorName, action, target, oldValue, newValue, details, ip }`.

| Call | Result |
|------|--------|
| `GET /api/admin/system/audit?page=0&size=50&action=&actor=&from=&to=` | `{ items, page, size, total }`, newest first, size up to 200, dates like `2026-10-30` |
| `DELETE /api/admin/system/audit/{id}` | `{ "deleted": 1 }`, 404 if missing |
| `POST /api/admin/system/audit/clear` body optional `{ "before": "2026-01-01" }` | `{ "deleted": n }` |

Writing an audit row never fails the action it records.

## Data cleanup

A job runs every night at 03:30 and removes old rows from a fixed list of tables. Each task has a system setting
`Cleanup_<Name>_Days` (0 turns it off; otherwise at least the task minimum, at most 3650). The settings appear in
`GET /api/admin/system/properties` like every other system setting.

| Task key | Setting | Default | Minimum | What it does |
|---|---|--:|--:|---|
| `payment_attempts` | `Cleanup_PaymentAttempts_Days` | 30 | 14 | Deletes expired attempts, never one in progress |
| `audit_log` | `Cleanup_AuditLog_Days` | 14 | 14 | Deletes old audit history (system, offers, coupons, roles) |
| `order_status_history` | `Cleanup_OrderStatusHistory_Days` | 14 | 14 | Deletes old status time line rows |
| `sync_queue_payload` | `Cleanup_SyncQueuePayload_Days` | 30 | 14 | Empties the stored content of finished (`DONE`) order rows; the rows stay |
| `device_logs` | `Cleanup_DeviceLogs_Days` | 30 | 14 | Deletes device logs |
| `sync_removals` | `Cleanup_SyncRemovals_Days` | 180 | 30 | Deletes old removals; keep longer than any device stays offline |
| `unpaid_orders` | `Cleanup_UnpaidOrders_Days` | 0 (off) | 15 | Deletes unpaid orders with their items, history and failed payments. See `docs/unpaid-orders.md`. 30 is a good value to start with |

- `GET /api/admin/system/cleanup` returns, per task: `key`, `label`, `description`, `setting`, `days`, `enabled`, `defaultDays`,
  `minDays`, `rows`, `eligible` (rows a run would remove now), `lastRunAt`, `lastRemoved`, `lastSource` (`SCHEDULED` or `MANUAL`), `lastError`.
- `POST /api/admin/system/cleanup/run` with `{"table": "<task key>"}` runs one task now (400 when it is off, 409 when a run is
  already in progress); with no body it runs every task that is on. Returns `items` (`key`, `days`, `removed`, `skipped`, `error`) and
  `removed`. Each call is recorded in the system audit as `CLEANUP_RUN`.
- Never cleaned: paid orders and what belongs to them (items, payments, coupon redemptions, offer applications), and inventory wastes. Unpaid orders are cleaned only when the `unpaid_orders` task is turned on.
- **Never delete rows of the integration queue (`sync_queue`).** The orders report shows "synced" from finished order rows. The
  cleanup only empties their content, and never touches `PENDING` or `FAILED` rows, so a failed row keeps its error.

## Working inside an organization (super-admin)

A super-admin has organization 0, so organization-scoped screens (dashboard, orders, products, reports, stores, employees,
devices, offers, receipt info) would be empty. The super-admin works inside one organization by sending the header
`X-Organization-Id: <id>` on each call. Nothing is stored on the session: each browser tab can use its own organization.

- The session then acts as that organization for every scoped call, with SUPER_ADMIN permissions.
- Header sent by someone who is not a super-admin: 403 (unless it is their own organization). Unknown organization: 400.
  A value that is not a positive whole number: 400. Non-user sessions (employees, devices) ignore the header.
- Without the header a super-admin reads empty lists, and write calls answer HTTP 409
  `{ "error": "ORGANIZATION_REQUIRED", "message": "Pick an organization first" }`, so nothing is written into organization 0.
  Writes that stay allowed without the header: `/api/auth/*`, `/api/admin/system/*`, `/api/admin/users*` (the organization is in the body),
  `/api/organizations`, `/api/config`, `/api/admin/organization/properties` (organization 0 = global defaults).
- The header is allowed in CORS.
- The app keeps the choice itself (memory or tab storage), sends it on every call, and may skip the organization list when
  there is only one organization.

`GET /api/organizations` lists organizations: `[ { id, name, slug } ]`. Optional `search` (name or slug), `page` and `size`
(size up to 200). Without them every organization is returned. The header `X-Total-Count` carries the total.
