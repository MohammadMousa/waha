# Combined sync pull (API level 7) - specification

Status: BUILT in the backend and tested (unit tests), not deployed. The kiosk and dashboard parts are not built. Backend: session `backend`. Kiosk app: session `kiosk`. Dashboard screen: session `admin`.
Nothing here changes an existing call: the per-table calls in `docs/offline.md` section 2 and the removals call keep working for old kiosks.

## 1. Why
One pull today is about 8 requests in a row (device-config, removals, six tables), each on its own connection. When a network loses new
connections now and then, the more requests a pull needs, the more likely one of them fails. The server is not the problem (tiny
tables, every answer under a second). A normal pull, where nothing changed, becomes ONE request.

## 2. The call: `POST /api/sync/pull`
Needs a device session with a store (same sign-in rules as the feeds: no or revoked session is 401, no store is 400).
It only reads, so a kiosk may repeat it after a lost connection, like any read.

Request:
```json
{
  "size": 200,
  "force": false,
  "removalsAfterId": 118,
  "removalsCheckedAt": "2026-11-25T08:00:00Z",
  "tables": {
    "products":              { "since": "2026-11-24T10:00:00Z", "afterId": 1650, "checkedAt": "2026-11-25T08:00:00Z" },
    "barcodes":              { "since": "2026-11-24T10:00:00Z", "afterId": 3100, "checkedAt": "2026-11-25T08:00:00Z" },
    "categories":            null,
    "payment-methods":       { "since": "2026-11-20T08:00:00Z", "afterId": 4, "checkedAt": "2026-11-24T08:00:00Z" },
    "store-payment-methods": { "since": "2026-11-20T08:00:00Z", "afterId": 9, "checkedAt": "2026-11-24T08:00:00Z" },
    "product-images":        { "since": "2026-11-24T10:00:00Z", "afterId": 410, "checkedAt": "2026-11-25T08:00:00Z" }
  }
}
```
- `tables`: the tables the device wants. A table with `null` (or an empty object) starts from the beginning, like a feed call without `since`.
  A table left out is not pulled. A name the server does not know is answered `{"skipped":"unknown"}` (so a newer kiosk never breaks on an older server).
- `since` and `afterId` are the saved `next` of the last committed page of that table, exactly as for the single feeds. The 10 second overlap
  and upsert rules in `docs/offline.md` section 2 stay the same.
- `size`: optional, rows per table per answer. Default `sync_pull_patch_size`, capped at 500, as for the feeds.
- `checkedAt`: when the kiosk last FINISHED checking this table (the `serverTime` of the answer in which the table came back `last:true`). Absent = never.
  `removalsCheckedAt` is the same for removals. Used for the expiration period (section 4).
- `force`: `true` on a manual pull (the tap or scan command): the expiration periods are ignored for this call. Switched-off tables are still skipped.
- `removalsAfterId`: the saved removals position. Omitted or `null` = starting position only (nothing listed), exactly like
  `GET /api/sync/removals` without `afterId`.

Response `200`:
```json
{
  "serverTime": "2026-11-25T09:00:00Z",
  "deviceConfig": { "deviceId": "...", "deviceNo": 1, "storeId": 7, "organizationId": 2, "currency": "SAR", "vatRate": 0.15,
                    "pricesIncludeVat": true, "shortCode": "WH", "invoiceBaseUrl": "https://...", "lastReferenceSerial": 233 },
  "tableConfig": { "products": { "active": true, "ttlSeconds": 10800 }, "barcodes": { "active": true, "ttlSeconds": 10800 },
                   "categories": { "active": true, "ttlSeconds": 86400 }, "payment-methods": { "active": true, "ttlSeconds": 0 },
                   "store-payment-methods": { "active": true, "ttlSeconds": 0 }, "product-images": { "active": false, "ttlSeconds": 0 },
                   "removals": { "active": true, "ttlSeconds": 0 } },
  "removals": { "items": [], "last": true, "next": { "afterId": 118 }, "resetRequired": false },
  "tables": {
    "products":              { "items": [], "last": true,  "next": { "since": "...", "afterId": 1650 } },
    "barcodes":              { "items": [ ... ], "last": false, "next": { "since": "...", "afterId": 3300 } },
    "categories":            { "skipped": "fresh" },
    "payment-methods":       { "items": [], "last": true, "next": { "since": "...", "afterId": 4 } },
    "store-payment-methods": { "items": [], "last": true, "next": { ... } },
    "product-images":        { "skipped": "disabled" }
  }
}
```
- `deviceConfig`: the same body as `GET /api/sync/device-config`, always included (it is tiny), so the kiosk needs no separate call.
- `tableConfig`: the organization's table settings now, for all 7 names (section 4): `active` and `ttlSeconds` (0 = no expiration period). The kiosk saves it
  from every answer and uses it to skip tables before it sends (section 3). The server checks every request too, so an old or stale kiosk is covered.
- `removals`: the same body as `GET /api/sync/removals` (`items`, `last`, `next.afterId`, `resetRequired`), without its own `serverTime`.
- Each table in `tables` has the same `items`, `last` and `next` as its single feed call. Inactive rows are still included (`active:false`).
- **Order of reading.** The server reads the newest removal id BEFORE it queries the tables, so a removals position never comes later than the rows next to it.
- **Row budget.** One answer carries at most **500 rows in total** (removals items count too). The server fills in this order and stops when the
  budget is used: removals, products, barcodes, categories, payment-methods, store-payment-methods, product-images. A table that got no room is
  answered `"deferred": true`, `last:false`, with the cursor the device sent (unchanged). A table cut off mid-way has `last:false` as usual.
- `skipped: "disabled"`: the organization switched that table off. `skipped: "fresh"`: the table has an expiration period and the device's `checkedAt` is
  younger than it. In both cases no `items`, no query, and the device keeps its local rows and its cursor. The same two values can appear for `removals`
  (as `"removals": { "skipped": "disabled" | "fresh" }`). A removals `skipped: "disabled"` still carries `next.afterId`, the newest removal id (section 4, removals).

## 3. How the kiosk uses it
1. Read `api_level` from `GET /api/config`. Level 7 or higher: use `POST /api/sync/pull`. Lower, or the call answers 404: use the old per-table
   way, and keep that choice for the rest of the app run.
2. Before sending, build `tables` from the saved cursors, leaving out: tables with `active:false` in the saved `tableConfig`, and (unless this is a manual pull)
   tables whose `checkedAt` + `ttlSeconds` is still in the future, judged by server time (the last answer's `serverTime` plus the time passed since, on a monotonic clock).
   When unsure, send the table: the server decides. With no saved `tableConfig` (first pull, fresh install) send every table. Save `tableConfig` from every answer.
   Set `checkedAt` of a table to the answer's `serverTime` when it came back `last:true`; `skipped: "fresh"` leaves it unchanged.
3. Apply the answer in this order: `deviceConfig` (save), `removals` (delete rows, then save `next.afterId`; if `resetRequired`, clear all table
   cursors and pull every table from the start), then each table in the order of section 2. For each table: write the rows with upsert, then save its cursor.
   Save a cursor only after its rows are committed. A lost or cut answer saves nothing.
4. Repeat the call while any table has `last:false` and the previous call moved at least one cursor. Pause the loop if a customer becomes active,
   as today. When every table is `last:true` (or skipped) the pull is complete. A pull with nothing new is exactly one request.
5. A table answered `skipped` (`disabled` or `fresh`) counts as complete for that pull. Never treat `skipped` as an error. A manual pull sends `force:true`.
6. The rule "no offline sales before `products`, `barcodes` and `payment-methods` each completed one full pull" is unchanged. If one of them is switched
   off and was never pulled, the kiosk stays server-first for it (section 4), it does not block selling while the server answers.
7. The status call (`POST /api/sync/status`) stays separate and unchanged.
8. Retries: 1.0.12 rules apply (a lost connection is repeated on a fresh connection). Any server answer (200, 401, 429, 503, 500) is handled as before.

## 4. Table config: on/off and expiration (new dashboard screen)
Per organization, an admin controls every sync table: switch it off (for good or for a while) and/or give it an expiration period (how long a kiosk may go
without checking it again). All 7 can be set: products, barcodes, categories, payment-methods, store-payment-methods, product-images and removals.

New table `sync_table_config` (migration V31), one row per organization and table (each company has its own settings):

| Column | Meaning |
|---|---|
| `organization_id`, `table_name` | primary key; `table_name` is one of the 7 names |
| `active` | 1 = pulled (default), 0 = skipped |
| `expiration_period` | NULL = checked at every pull (default). Else `<number><S/M/H/D>` like `30S`, `5M`, `2H`, `1D`; allowed 10 seconds to 30 days |
| `description` | optional note for admins (the screen shows a default text per table when empty) |
| `created_at`, `updated_at` | |

Rules:
- No row for a table means ON with no expiration period. Nothing changes after the update until an admin sets something.
- The server reads the table with a short in-memory cache (changes apply within a minute). `tableConfig` in the answer comes from it.
- **Off:** the combined pull answers `skipped: "disabled"`. The old single call for that table answers an empty last page with the cursor unchanged, so an older
  kiosk simply pulls nothing (for `removals`: empty last page, `resetRequired:false`). Local rows are never deleted. Switching back on resumes from the saved cursor.
- **Expiration period (combined pull only):** if the table has one, `force` is not true and the request's `checkedAt` is younger than it, the server answers
  `skipped: "fresh"` WITHOUT querying and without spending row budget. The kiosk applies the same rule before it sends (section 3), so both sides control the pull.
  A table with no `checkedAt` (never checked, or cut off before `last:true`) is always pulled. The old single calls ignore expiration periods.
  An expiration period shorter than `sync_pull_interval_minutes` has no effect.
- **Removals, switched off.** Removals are always RECORDED, whatever the switch says; the switch only stops them from being listed. The starting position is handed out
  even while removals is off: the answer is `"removals": { "skipped": "disabled", "next": { "afterId": <newest id> } }` (the old single call without `afterId` behaves the same:
  starting position, nothing listed; with `afterId` and removals off: empty last page, cursor unchanged, `resetRequired:false`). Kiosk rule: if it has NO saved removals position,
  it saves that `next.afterId`; if it already has one, it keeps its old one (never moves it while removals is off). When removals is switched on later, the kiosk sends its
  saved position and receives every deletion made in between, so nothing is lost.
- **Limit for a removals position.** Removal records are cleaned by the system setting `Cleanup_SyncRemovals_Days` (default 180, lowest allowed 30; super admin, see
  `docs/system-configuration.md`). A position older than the cleaned part is answered `resetRequired:true`, and the kiosk then pulls all tables in full (the safety net, also
  after a long switch-off). Because the lowest setting is 30 days, the expiration period of `removals` is capped at **7 days** (`7D`; the server answers 400 above it), and the
  screen should keep it far shorter in practice.
- Examples an admin might set: products and barcodes `3H`; categories, payment-methods, store-payment-methods, removals `1D`; product-images off.
- While a table is off or cooled, its changes reach kiosks late. The screen warns for `products`, `barcodes`, `payment-methods`, `store-payment-methods` and
  `removals` (new items and prices, methods switched off on the server, and deleted rows reach kiosks late). `categories` and `product-images` carry no warning.
- A kiosk that has never pulled a table because it is off treats that table's local copy as incomplete: it works server-first for it, like `caching` mode
  (the rule for an unfinished first download in `docs/offline.md`).
- Admin calls (permission `MANAGE_SETTINGS`, normal organization scope; a super admin uses `X-Organization-Id`):
  - `GET /api/admin/sync/tables` -> `[ { "table": "categories", "active": true, "expirationPeriod": "1D", "description": "...", "sensitive": false }, ... ]`
    for all 7 names in this order: products, barcodes, categories, payment-methods, store-payment-methods, product-images, removals. `expirationPeriod` is null when none. `sensitive: true` for the five named above.
  - `PUT /api/admin/sync/tables/{table}` body with any of `active`, `expirationPeriod` (null clears it), `description` (omitted keeps the current value) -> `204`.
    A bad period format or range is `400 {message}`; an unknown table `404`.
- Dashboard screen: a list of the tables, each with a switch, an expiration field and a description. Setting a `sensitive` table off, or giving it a period, asks
  for a confirmation that shows the warning. Show a note that devices pick a change up at their next pull and that an expiration period only matters when it
  is longer than the pull interval. Needs `api_level` 7 or higher; below that, hide the screen.

## 5. What is NOT in this change
- No `device_sync_summary` table (not now). The expiration period needs no server-side per-device state: the kiosk sends `checkedAt` with the request.
- No server-side result cache. If ever needed: keep the newest change position of each table in memory.
- Status is not folded into the pull.

## 6. Work split and checks
Backend (`backend` session, repo `~/git/waha`): the combined call, the row budget, `sync_table_config` + V31, the admin calls, `ApiLevel.CURRENT` = 7 and its row in
`docs/api-levels.md`, tests (nothing changed = one small answer; expiration period: fresh, expired, no `checkedAt`, `force`, cut-off table; `tableConfig` in every answer; budget cut and `deferred`; first sync loops to completion with the same rows as the single feeds;
disabled table in both ways; every table can be switched off and back on, in both calls; org isolation; 401/400), update `docs/offline.md`. Deploy order: backend first, then kiosk and dashboard.
Kiosk (`kiosk` session): section 3, with the fallback to the old way; save `tableConfig`; send `checkedAt` and `force`; tests for the loop, `resetRequired`, `skipped` (disabled and fresh), `deferred`, 404 fallback and a lost connection mid-answer.
Dashboard (`admin` session): section 4 screen and its `api_level` check.
