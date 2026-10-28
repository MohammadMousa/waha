# Account sessions

Dashboard users and employees can be signed in on several devices at once. Tokens never expire, so
sessions are listed and ended explicitly. Device (kiosk) sessions work the same way and keep their own
endpoints under `/api/admin/devices/{id}/sessions`. Available from `api_level` 3.

A session is never returned with its token. Each row carries a `ref` (a hash of the token) used to end it.

## Row

```json
{ "ref": "…", "createdAt": "…", "lastSeenAt": "…", "appVersion": "1.0.2", "mode": "NORMAL",
  "storeId": 1, "storeName": "Main", "info": { "os": "Windows", "browser": "Chrome" },
  "current": true, "latest": true }
```

- `lastSeenAt` is kept in memory and written to the database in batches (every `last_seen_flush_minutes`,
  default 60). It is exact in the list, delayed in the database.
- `info` is null when the client sent nothing. `current` appears only on the caller's own list.
- Newest activity first.

## Calls

| Call | Who | Result |
|------|-----|--------|
| `GET /api/admin/users/{id}/sessions` | `MANAGE_USERS` | `{ "items": [...] }` |
| `DELETE /api/admin/users/{id}/sessions/{ref}` | `MANAGE_USERS` | `{ "disconnected": 1 }`, 404 if the ref is not that user's |
| `POST /api/admin/users/{id}/sessions/delete` body `{ "refs": [...] }` | `MANAGE_USERS` | `{ "disconnected": n }`, up to 200 refs |
| same three under `/api/admin/employees/{id}/sessions` | `MANAGE_EMPLOYEES` | same |
| `GET /api/auth/me/sessions` | any dashboard user or employee | own sessions, `current` marks this one |
| `DELETE /api/auth/me/sessions/{ref}` | same | `{ "disconnected": 1 }` |
| `POST /api/auth/me/sessions/sign-out-others` | same | `{ "signedOut": n }`, this session stays |

Device sessions get 400 on the `/api/auth/me/sessions` calls. Existing `POST /api/auth/logout` still ends only the caller's own session.

## Login info

Logins (`POST /api/auth/login`, `/api/pos/auth/login`, `/api/kiosk/auth/login`) accept, inside `sessionProperties`,
the keys `os`, `osVersion`, `model` and `browser`. Other keys are ignored, values are cut at 100 characters.
For a browser login the browser and OS are read from the user agent when not sent. Stored once, at login.

## Cap

System property `Account_Sessions_Cap` (global, organization 0), default 5, `0` = off. When a dashboard user
or employee logs in and has more sessions than the cap, the sessions with the oldest activity are ended.
The new session is never ended. Device sessions are never counted or ended.
