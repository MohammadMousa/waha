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

Only critical actions are written, so the table stays small:

| action | target | When |
|--------|--------|------|
| `SETTING_CHANGED` | setting key | a system setting changed (old and new value) |
| `CACHE_CLEARED` | cache name or `all` | cache cleared |
| `CACHE_SAVED` | `last-seen` or `all` | pending last-seen data saved |
| `LOCKOUTS_CLEARED` | - | login lockouts cleared |
| `AUDIT_ROW_DELETED` | row id | one audit row deleted |
| `AUDIT_CLEARED` | `all` or `before <date>` | audit cleared (this one row is kept) |

Row: `{ id, createdAt, actorUserId, actorName, action, target, oldValue, newValue, details, ip }`.

| Call | Result |
|------|--------|
| `GET /api/admin/system/audit?page=0&size=50&action=&actor=&from=&to=` | `{ items, page, size, total }`, newest first, size up to 200, dates like `2026-10-30` |
| `DELETE /api/admin/system/audit/{id}` | `{ "deleted": 1 }`, 404 if missing |
| `POST /api/admin/system/audit/clear` body optional `{ "before": "2026-01-01" }` | `{ "deleted": n }` |

Writing an audit row never fails the action it records.

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
