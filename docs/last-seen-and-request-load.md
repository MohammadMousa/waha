# Last seen, app version and request load

How the backend records "when was this session last active" and "which app version is it", why that
is cheap even when devices call often, and what to do if a device (or many devices) start hammering
the server. Reference for backend, admin, kiosk and client work.

## 1. What is recorded

| Value | Where | When it changes |
|-------|-------|-----------------|
| `last_seen_at` | `user_sessions` and `devices` | every authenticated call (in memory), written to the database in batches |
| `app_version` | `user_sessions` and `devices` | at login (written straight away); on later calls only if the client sends it |

Both appear in the session lists (`docs/account-sessions.md`) and in the device status report.

## 2. How the in-memory tracker works (LastSeenTracker)

- Every authenticated call runs `touch(token, deviceId, appVersion)`. It does **not** touch the database.
  It updates in-memory maps keyed by **session token** (and by device id for devices).
- Memory entry per session: `{ time of last call, app version }`. A new call **overwrites** the same entry
  with the newest time; the app version is kept when a call does not send one. The map therefore holds
  **one entry per session, never one per call**. 10 devices calling 1000 times each = still 10 entries.
- A timer (`last_seen_flush_minutes`, global property, default 60) writes each changed entry to the database
  once: `UPDATE user_sessions SET last_seen_at, app_version WHERE token`, and the same for the device row.
  So the database receives **one small update per active session per flush**, carrying only the latest values,
  however many calls came in between.
- A login writes straight away (`touchAndSave`), so the version survives a server restart soon after login.
- Shutdown flushes what is pending. A crash can lose up to one flush interval of "last seen" times; nothing else.
- Ending a session (logout, sign-out, cap, delete) also removes its memory entry.
- Readers (device report, session lists) look at memory first and the database second, so screens show the
  fresh time even though the database lags by up to one interval.

### Answer to "10 devices hammering = the same 10 records going to the database?"

Yes. Memory is updated on every call, but each call overwrites the same entry. Between two flushes the
database sees at most one write per session, with the newest values. Hammering 10 devices costs
10 updates per flush, the same as 10 quiet devices.

## 3. What a hammering device really costs

The in-memory tracker is not the expensive part. The expensive part is the **session lookup**: every
authenticated call (`requireSession`) runs one database query to find the session and to check that the
device or employee is still enabled. Tokens never expire, so this check cannot be skipped.

| Cost per call | Today |
|---------------|-------|
| Session lookup | 1 read query (joins users / employees / devices) |
| Last-seen update | memory only |
| App version update | memory only |
| Flush | 1 write per session per interval |

Adding the app version to token checks (`GET /api/auth/me`, `GET /api/kiosk/auth/me`, employee check) adds
**no database work**: it is the same memory entry.

Login is protected separately (see section 5). Reads and writes of real work (orders, quotes) are real work
and are not "hammering".

## 4. Hammering: how it shows up and the options

Typical cause: a device in a restart loop. It starts, finds its saved token, calls the token check, crashes,
and repeats. One device can make several calls per second. 10 or 100 such devices multiply it.

| Option | What it does | Cost / risk | Status |
|--------|--------------|-------------|--------|
| Memory last-seen + batched flush | Keeps last-seen and version writes off the database | none | **built** |
| Short cache of the session lookup | Reuses the session row for a few seconds instead of one query per call | A disabled device or ended session keeps working for the cache time (seconds). Must be cleared on logout, sign-out, cap and disable | not built; add when the lookup shows up in load |
| Per-token rate limit on token checks | e.g. above 60 calls a minute answer HTTP 429 with `Retry-After` | A wrong limit blocks a healthy device; needs a number from real traffic | not built; add only if logs show a loop |
| Ban the device | Refuse the device completely until an admin lifts it | Locks out a working kiosk when the rule is wrong; needs a lift screen | not recommended; the admin can already disable a device (it gets 401 at once) |
| Client back-off | The app waits longer after each failure (1 s, 2 s, 4 s ... up to a minute) | App change | recommended for kiosk and client apps |

Recommended order if load ever becomes a problem: client back-off, then the short lookup cache, then the
per-token limit. A ban is the last resort and the existing "disable device" already covers it by hand.

## 5. Protections that already exist

These come from the code, not from measured incidents. No hammering incident is recorded in the repository.

| Case | Protection |
|------|------------|
| Many wrong logins (user, employee, device) | 5 failures per account, then locked 15 minutes with HTTP 429 and `Retry-After` |
| Many wrong logins from one address | 20 failures per address, then HTTP 429 |
| Wrong PIN checks on a saved kiosk login | 5 failures in 5 minutes, then a 5 minute cooldown |
| Busy devices writing to the database on every call | In-memory last-seen with batched writes (this document) |
| Devices polling for status | `heartbeat_minutes` organization property; 0 turns monitoring off |
| Automatic log uploads | `max_log_uploads_per_hour` organization property (1 to 60, default 3) |
| Sessions piling up per account | `Account_Sessions_Cap` (default 5) at login |
| Retry of a checkout on a flaky connection | The client order id makes order creation a safe repeat |
| A disabled device or employee | Refused with 401 on the next call, token or not |

## 6. Rules for clients

1. Send `appVersion` in the login body (`POST /api/auth/login`, `/api/pos/auth/login`, `/api/kiosk/auth/login`).
2. Optionally send `X-App-Version` on the token check (`GET /api/auth/me`, `GET /api/pos/auth/me`,
   `GET /api/kiosk/auth/me`). Web builds can send it too; the header is allowed in CORS.
3. Do not retry a failed token check in a tight loop. Back off, and treat 401 as "log in again".
4. Do not call the token check on a timer for monitoring. The kiosk heartbeat exists for that and is controlled
   by `heartbeat_minutes`.

## 7. Settings

| Key | Scope | Default | Meaning |
|-----|-------|---------|---------|
| `last_seen_flush_minutes` | global | 60 | minutes between database writes of last-seen and version (1 to 1440) |
| `heartbeat_minutes` | organization | 0 | kiosk heartbeat interval, 0 = off |
| `Account_Sessions_Cap` | global | 5 | sessions per user or employee, 0 = off |
