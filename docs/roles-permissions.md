# Roles, Permissions & Auth Domains

Phase 3 splits authentication into three independent domains. Each domain
has its own login endpoint, its own table, and its own session type.
All three share the same `user_sessions` table and the same `Authorization: Bearer <token>` header.

---

## Three auth domains

| Domain | Table | Login | PIN / Password | Used by |
|--------|-------|-------|----------------|---------|
| **Admin** | `users` | `POST /api/auth/login` | bcrypt password | Admin app |
| **Employee / POS** | `employees` | `POST /api/pos/auth/login` | 4-digit plain PIN | POS app |
| **Device / Kiosk** | `devices` | `POST /api/kiosk/auth/login` | 4-digit plain PIN | Kiosk app |

There is **no FK** between `employees` / `devices` and `users`. They are fully
independent identities. An employee is not a user; a device is not a user.

---

## Roles

| Role | Lives in | Scope |
|------|----------|-------|
| `SUPER_ADMIN` | `users` + `user_roles` | Platform-wide (`scope_type = SYSTEM`) |
| `ORGANIZATION_OWNER` | `users` + `user_roles` | Organization (`scope_type = COMPANY`) |
| `BRANCH_ADMIN` | `employees` + `employee_roles` | Branch / Branch-group |
| `OPERATOR` | `employees` + `employee_roles` | Branch / Branch-group |
| `CASHIER` | `employees` + `employee_roles` | Branch / Branch-group |
| `KIOSK` | **static** — inherent to every device session | Branch (device's assigned store) |

`SUPER_ADMIN` and `ORGANIZATION_OWNER` only exist in the `users` table and are
managed via the admin app. They never appear in `employees`.

---

## Permission map

| Permission | KIOSK | CASHIER | OPERATOR | BRANCH_ADMIN | ORG_OWNER | SUPER_ADMIN |
|---|:---:|:---:|:---:|:---:|:---:|:---:|
| `VIEW_PRODUCTS` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `EDIT_PRODUCTS` | | | ✅ | ✅ | ✅ | ✅ |
| `MANAGE_CATEGORIES` | | | ✅ | ✅ | ✅ | ✅ |
| `VIEW_OWN_ORDERS` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `VIEW_ORDER_HISTORY` | | ✅ | ✅ | ✅ | ✅ | ✅ |
| `VIEW_ALL_ORDERS` | | ✅ | ✅ | ✅ | ✅ | ✅ |
| `PROCESS_ORDERS` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `MANAGE_USERS` | | | | | | ✅ |
| `MANAGE_EMPLOYEES` | | | | ✅ | ✅ | ✅ |
| `MANAGE_DEVICES` | | | | ✅ | ✅ | ✅ |
| `MANAGE_STORES` | | | | ✅ | ✅ | ✅ |
| `MANAGE_SYSTEM` | | | | | | ✅ |
| `EDIT_RESOURCES` | | | ✅ | ✅ | ✅ | ✅ |
| `VIEW_INVENTORY` | | ✅ | ✅ | ✅ | ✅ | ✅ |
| `PROCESS_INVENTORY` | | | ✅ | ✅ | ✅ | ✅ |
| `MANAGE_INVENTORY` | | | | ✅ | ✅ | ✅ |

Permissions are returned as a string array in every login response. Frontend
should use this array for UI gating — don't re-derive from role name.

---

## API reference — Admin app (users)

### `POST /api/auth/login`
Standard admin login. Same endpoint as before.
```json
// Request
{"username": "admin@waha", "password": "secret"}

// 200
{"token": "...", "userId": 1, "organizationId": 5, "storeId": null, "mode": null}

// 401 → {"message": "Invalid username or password"}
```

### `GET /api/organizations`
SUPER_ADMIN uses this after login to pick an org (frontend auto-selects the first one).
```json
// 200 — requires SUPER_ADMIN session
[{"id": 1, "name": "Waha Co."}, {"id": 2, "name": "Demo Org"}]
```

### `GET /api/admin/users`
List all users for the session's org. Requires `MANAGE_USERS`.
```json
// 200
[{"id": 1, "username": "admin@waha", "organizationId": 5, "roleName": "ORGANIZATION_OWNER"}]
```

### `POST /api/admin/users`
Create a new admin user + assign role. Requires `MANAGE_USERS`.
```json
// Request
{"username": "jane", "password": "secret", "role": "BRANCH_ADMIN"}

// 200
{"id": 7}

// 409 → {"message": "Username already taken"}
```

### `PATCH /api/admin/users/{id}`
Update password or role. Requires `MANAGE_USERS`.
```json
// Request — all fields optional
{"password": "newpass", "role": "OPERATOR"}

// 200 (empty body)
```

### `DELETE /api/admin/users/{id}`
Delete a user. Requires `MANAGE_USERS`.

### `GET /api/admin/users/roles`
Returns roles the caller is allowed to assign.
```json
// SUPER_ADMIN        → ["SUPER_ADMIN", "ORGANIZATION_OWNER"]
// ORGANIZATION_OWNER → ["ORGANIZATION_OWNER"]
```
Branch-level roles (BRANCH_ADMIN, OPERATOR, CASHIER) belong in employees, not users.

---

## API reference — Employee management (admin app manages POS staff)

Base path: `/api/admin/employees` — requires `MANAGE_EMPLOYEES` permission.
All endpoints are scoped to the session's `organizationId`.

### `GET /api/admin/employees`
```json
// 200
[{
  "id": 3,
  "username": "sara",
  "firstName": "Sara", "lastName": "Ali",
  "gender": "FEMALE", "birthDate": "1995-04-12",
  "email": "sara@waha.com", "phone": "0501234567",
  "hiredAt": "2024-01-01", "address": null, "notes": null,
  "avatarResourceId": null,
  "enabled": true,
  "createdAt": "2025-01-01T10:00:00",
  "roleName": "CASHIER",
  "storeId": 5,
  "storeName": "Branch 1"
}]
```

### `POST /api/admin/employees`
```json
// Request — username + pinCode required; everything else optional
{
  "username": "sara",
  "pinCode": "1234",
  "firstName": "Sara", "lastName": "Ali",
  "gender": "FEMALE",
  "birthDate": "1995-04-12",
  "email": "sara@waha.com",
  "phone": "0501234567",
  "hiredAt": "2024-01-01",
  "address": "...",
  "notes": "...",
  "avatarResourceId": null,
  "enabled": true,
  "role": "CASHIER",      // optional: CASHIER | OPERATOR | BRANCH_ADMIN
  "storeId": 5            // required if role is provided
}

// 200 → {"id": 3}
// 400 → {"message": "pinCode must be 4 digits"}
// 409 → {"message": "Username already taken"}
```

### `GET /api/admin/employees/{id}`
Returns the employee + a `branches` list (all assigned stores).

### `PATCH /api/admin/employees/{id}`
All fields optional. Send only what changes.
```json
{
  "pinCode": "5678",
  "firstName": "Sara",
  "enabled": false,
  "role": "OPERATOR",
  "storeId": 6
}
// Sending role + storeId replaces the current role assignment entirely.
// 200 (empty body)
```

### `DELETE /api/admin/employees/{id}`

### `POST /api/admin/employees/{id}/stores`
Assign an additional store to an employee.
```json
{"storeId": 7}
// 200 (empty body)
```

### `DELETE /api/admin/employees/{id}/stores/{storeId}`
Remove a store assignment.

---

## API reference — Device management (admin app manages kiosk devices)

Base path: `/api/admin/devices` — requires `MANAGE_DEVICES` permission.
All endpoints are scoped to the session's `organizationId`.

### `GET /api/admin/devices`
```json
// 200
[{
  "id": 1,
  "username": "kiosk-branch1",
  "name": "Branch 1 Kiosk",
  "deviceKey": "A3F9C12B8E4D7F01",
  "deviceType": "KIOSK",
  "organizationId": 5,
  "storeId": 5,
  "storeName": "Branch 1",
  "enabled": true,
  "createdAt": "2025-01-01T10:00:00"
}]
```

### `POST /api/admin/devices`
```json
// Request
{
  "username": "kiosk-branch1",
  "pinCode": "9999",
  "storeId": 5,            // required
  "name": "Branch 1 Kiosk",
  "deviceType": "KIOSK",   // defaults to "KIOSK" if omitted
  "enabled": true
}

// 200 → {"id": 1, "deviceKey": "A3F9C12B8E4D7F01"}
// deviceKey is auto-generated — store it on the device for identification
// 400 → {"message": "storeId is required"}
// 400 → {"message": "pinCode must be 4 digits"}
// 409 → {"message": "Username already taken"}
```

### `GET /api/admin/devices/{id}`
### `PATCH /api/admin/devices/{id}`
```json
// All fields optional
{"name": "New Name", "pinCode": "0000", "enabled": false, "storeId": 6, "deviceType": "KIOSK"}
```
### `DELETE /api/admin/devices/{id}`

---

## API reference — POS app login

Employee sessions have **no storeId in the session**. The working store is sent by
the frontend as a field in each request that needs it (e.g. `POST /api/inventory/visits`
body includes `"storeId"`). Permissions are resolved once at login from all the
employee's roles across all branches and returned in the login response — no
second call needed.

### `POST /api/pos/auth/login`
```json
// Request
{"username": "sara", "pinCode": "1234", "organizationId": 5}

// 200
{
  "token": "...",
  "employeeId": 3,
  "organizationId": 5,
  "permissions": ["VIEW_PRODUCTS", "PROCESS_ORDERS", "VIEW_INVENTORY", "PROCESS_INVENTORY"],
  "stores": [
    { "id": 5, "name": "alj", "displayName": {"ar": "الجزيرة", "en": "Aljazeera"} },
    { "id": 6, "name": "epc", "displayName": {"ar": "بنك ايبك", "en": "EPC Bank"} }
  ]
}

// 401 → {"message": "Invalid username or PIN"}
// 401 → {"message": "Account is disabled"}
```

`stores` — all stores the employee is assigned to (from `employee_stores`).
Frontend uses this to show a branch picker; the chosen `storeId` is then sent
with subsequent inventory calls. No store-selection API call is needed.

### `POST /api/pos/auth/logout`
`Authorization` header, empty 200 body.

---

## API reference — Kiosk app login

### `POST /api/kiosk/auth/login`
```json
// Request
{"username": "kiosk-branch1", "pinCode": "9999", "organizationId": 5}

// 200
{
  "token": "...",
  "deviceId": 1,
  "organizationId": 5,
  "storeId": 5,
  "mode": "KIOSK",
  "permissions": ["VIEW_PRODUCTS", "VIEW_OWN_ORDERS", "PROCESS_ORDERS"]
}

// 401 → {"message": "Invalid username or PIN"}
// 401 → {"message": "Device is disabled"}
```

### `POST /api/kiosk/auth/logout`
`Authorization` header, empty 200 body.

---

## Session behaviour — what's the same for all three

- Header: `Authorization: Bearer <token>` on every authenticated request
- Token is opaque (not JWT) — looked up server-side on every call
- TTL: 30 days (configurable via `waha.session.ttl-days`)
- `storeId` in session: set for kiosk (device's store); always null for employee sessions — employees pass storeId per-request
- `mode` in the session drives backend validation policy (`KIOSK` / `NORMAL`)

---

## SUPER_ADMIN org handling

`SUPER_ADMIN` has `organization_id = 0` in the DB (platform-level).
After login, frontend calls `GET /api/organizations` and auto-selects the first org.
All subsequent admin operations are scoped to that org. There is no "system-level screen" yet — org is always set before any action.

---

## Error shape

All errors are `{"message": "..."}`. Match on HTTP status, use `message` for display/logging.

| Status | Meaning |
|--------|---------|
| 400 | Validation failure — bad input |
| 401 | Missing / expired / invalid session |
| 403 | Valid session but insufficient permissions |
| 404 | Resource not found |
| 409 | Conflict — duplicate username, already paid, etc. |
