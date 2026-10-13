# API levels

The backend reports which client-facing features it has in one number, `api_level`.
It is returned by the public config endpoint, `GET /api/config`, together with the other properties.

```json
{ "default_language": "en", "api_level": "1", ... }
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

## Endpoints per level

### Level 1

- `GET  /api/admin/device-status`
- `GET  /api/admin/devices/{id}/sessions`
- `DELETE /api/admin/devices/{id}/sessions/{ref}`
- `POST /api/admin/devices/{id}/sessions/delete`
- `POST /api/kiosk/heartbeat`
- `POST /api/orders/{orderId}/late-approval`
- `appVersion` in the kiosk login body, `X-App-Version` header on `GET /api/kiosk/auth/me`
