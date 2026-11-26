# Sign-in

There are three separate kinds of sign-in. Each has its own accounts; all use `Authorization: Bearer <token>`. Roles and permissions: [roles-permissions.md](../roles-permissions.md).

| Kind | Login | Credential |
|---|---|---|
| Account (dashboard, customers) | `POST /api/auth/login` | username + password |
| Employee (POS) | `POST /api/pos/auth/login` | username + 6-digit PIN |
| Kiosk device | `POST /api/kiosk/auth/login` | username + 6-digit PIN |

## Sessions

- A token does not expire by time. It ends at logout, or when the server revokes it (for example a password or PIN change, a disconnect, or disabling the account). A disabled employee or device is refused on every call.
- Account and employee sessions are capped per account (global property `Account_Sessions_Cap`, default 5, 0 = no cap). A new login ends the sessions with the oldest activity. Details: [account-sessions.md](../account-sessions.md).
- Use the returned `permissions` list to show or hide features. Do not derive it from the role name. The server checks permissions again on each protected call.
- `organizationId` is optional at login. The account login, kiosk login, employee login and registration use organization `1` for most purposes when it is left out; send it whenever the organization is known.

## Lockout

5 failed logins for one account lock it for 15 minutes; 20 failures from one IP address lock that address. Locked answers are `429` with `Retry-After`:

```json
{"code": "ACCOUNT_LOCKED", "message": "...", "retry_after_seconds": 900}
```
For an IP lock the code is `TOO_MANY_REQUESTS`.

A wrong password or PIN is `401`:

```json
{"code": "INVALID_CREDENTIALS", "message": "...", "attempts_remaining": 4, "max_attempts": 5}
```

## Account sign-in (`/api/auth`)

### `POST /login`
```json
{"username": "jane", "password": "...", "organizationId": 1,
 "sessionProperties": {"mode": "NORMAL"}, "appVersion": "1.0.0"}
```
Only `username` and `password` are required. `mode` is `NORMAL`, `KIOSK` or `SHOPPING` (any other value is `400`).

Answer:
```json
{"token": "...", "userId": 7, "username": "jane", "storeId": 5, "defaultStoreId": 5, "mode": "NORMAL",
 "properties": {"...": "..."}, "roleName": null, "permissions": ["..."]}
```
At login `storeId` is the user's assigned branch, or the organization's default store, and `defaultStoreId` carries the same value. In later answers `defaultStoreId` is the organization's default store. Both can be `null`.

### `POST /register`
Body: `username`, `password`, optional `organizationId` and `sessionProperties`. Same answer as login. `409` if the username is taken.

### `POST /guest`
No body. Creates a temporary account in organization `1`, in `SHOPPING` mode, and returns the same answer as login.

### `GET /me`
The same fields as login without `token`.

### `POST /store`
Chooses the session's store: `{"storeId": 5, "sessionProperties": {"mode": "KIOSK"}}` (`sessionProperties` optional). Answers like `/me`, with permissions for that store. `400` if the store cannot be selected. Without the manage-stores permission only public, active stores can be selected.

### `PATCH /me/password`
`{"currentPassword": "...", "newPassword": "..."}`. The new password needs at least 8 characters. Other sessions of the account are signed out; this one stays. A wrong current password is `401`, so a client should not treat that answer as a lost session.

### `POST /logout`
Empty `200`.

Listing and disconnecting your own sessions (`/me/sessions`): [account-sessions.md](../account-sessions.md).

## Employee sign-in (`/api/pos/auth`)

`POST /login` with `{"username", "pinCode", "organizationId"?, "sessionProperties"?, "appVersion"?}` returns:

```json
{"token": "...", "employeeId": 3, "organizationId": 1, "employeeName": "...", "roleName": "...",
 "permissions": ["..."], "stores": [{"id": 5, "name": "...", "displayName": {"en": "..."}}]}
```
The employee session has no selected store: send `storeId` on the calls that need it. `permissions` combines the employee's roles across branches. `GET /me` returns the same data (with `username`, without `token`). `POST /logout` ends the session.

`401` with only `message` means the account is disabled.

## Kiosk sign-in (`/api/kiosk/auth`)

`POST /login` with `{"username", "pinCode", "organizationId"?, "sessionProperties"?, "appVersion"?}` returns:

```json
{"token": "...", "deviceId": 1, "organizationId": 1, "storeId": 5, "mode": "KIOSK", "permissions": ["..."]}
```
The store comes from the device record. Device permissions are fixed.

| Call | Notes |
|---|---|
| `GET /me` | Device details, including `username` |
| `POST /pin/verify` | `{"pinCode": "123456"}` returns `{"valid": true/false}`. For unlocking screens on the device; the PIN is checked by the server and must not be stored on the device. 5 failures within 5 minutes cause a 5 minute cooldown (`429`) |
| `POST /logout` | Ends the session |

A disabled device gets `401` with only `message`.
