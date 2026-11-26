# API conventions

## Server address

| Where | Base URL |
|---|---|
| Production | the server's HTTPS address; nginx forwards `/api/` and `/resource/` to the backend |
| Local Docker, same machine | `http://localhost:8081` |
| Android emulator to local Docker | `http://10.0.2.2:8081` |
| Real device to local Docker | `http://<computer's LAN IP>:8081` |

The backend listens on 8080 inside its container. Docker publishes it as 8081.

## Format

Requests and answers are JSON unless a document says otherwise (invoices are HTML or PDF, uploads are multipart).
Names and descriptions of stores, categories and products are objects such as `{"ar": "...", "en": "..."}`. The client picks the language. A value can be `null`.

## Headers

| Header | When |
|---|---|
| `Authorization: Bearer <token>` | Calls that need a sign-in. Some public calls also use it to find the store and user |
| `X-App-Version` | Optional app version. Read by the "who am I" calls |
| `X-Organization-Id` | Super admin only: the organization to act in, per request |

A super admin without `X-Organization-Id` gets `409` `ORGANIZATION_REQUIRED` on most writes. A few paths are exempt (sign-in, system settings, users, organizations, config, organization properties, audit, profile, and the system-wide payment method switch and icon).

Tokens are random strings, not JWT. See [authentication.md](authentication.md).

## Store context

Many calls take `storeId`. Where a document says **store: optional**, the server uses the one sent, and otherwise the store saved in the session. With neither, the answer is `400`.

## Errors

Use the HTTP status first. The body is **not** the same shape everywhere:

| Shape | Used for |
|---|---|
| `{"message": "..."}` | Most errors |
| `{"error": "CODE", "message": "..."}` | Some business rules, e.g. coupons, shipping, `ORDER_TOTAL_NOT_POSITIVE`, `ORGANIZATION_REQUIRED` |
| `{"code": "...", "message": "...", ...}` | Sign-in failures and lockouts, with extra fields (see [authentication.md](authentication.md)) |
| `{"message": "...", "errors": {"field": "..."}}` | Some admin forms |

Show or log `message`. Branch only on the status and on `error` / `code`. Do not parse `message`. Errors the server does not handle itself, and a few calls that return no body, may not match any of these, so a client must cope with a missing `message`.

| Status | Meaning |
|---|---|
| 400 | Bad or missing input |
| 401 | Missing or invalid session, or wrong credentials |
| 403 | Signed in without the needed permission |
| 404 | Not found |
| 409 | Conflict with the current state |
| 413 | Upload too large |
| 422 | A business rule refused the request |
| 429 | Locked out or too many requests; see `Retry-After` |

## API level

`GET /api/config` is public. It returns settings as text key/value pairs, including `api_level`. Send `?orgId=<id>` to include that organization's values.
Check the level before using a newer feature. Rules and the feature table: [api-levels.md](../api-levels.md).
