# Audit history

One table (`audit_log`) records who changed what. It replaces the old `system_audit`, `offer_audit` and `coupon_audit` tables.

## Scopes

| Scope | What | Who can read it |
|-------|------|-----------------|
| `SYSTEM` | Super-admin actions: settings, caches, lockouts, audit deletes, cleanup runs, changes to built-in roles | Super admin only |
| `ORGANIZATION` | Inside one organization: offers, coupons, roles and role assignments | Super admin, and the organization's own owner or anyone holding `VIEW_ORGANIZATION_AUDIT` |

Entity types: `SYSTEM`, `OFFER`, `COUPON`, `ROLE`, `USER`, `WASTE`, `ORDER`.

Row: `{ id, createdAt, scope, organizationId, entityType, entityId, actorUserId, actorName, action, target, oldValue, newValue, details, ip }`.
For offers, coupons and roles `oldValue` and `newValue` hold the before and after content as JSON text.

## Calls (dashboard sessions only)

| Call | Who | Result |
|------|-----|--------|
| `GET /api/admin/audit?page=0&size=50&scope=ALL&type=&entityId=&action=&actor=&organizationId=&from=&to=` | See below | `{ items, page, size, total, types }`, newest first, size up to 200 |
| `DELETE /api/admin/audit/{id}` | Super admin | `{ "deleted": 1 }`, 404 if missing |
| `POST /api/admin/audit/delete` body `{ "ids": [1,2] }` | Super admin | `{ "deleted": n }`, at most 500 ids |
| `POST /api/admin/audit/clear` body optional `{ "before": "2026-01-01", "scope": "ALL" }` | Super admin | `{ "deleted": n }` |

Listing:
- **Super admin:** `scope` is `ALL` (default), `SYSTEM` or `ORGANIZATION`. `organizationId` narrows the organization rows. When the super admin works inside an organization (`X-Organization-Id`) and asks for `ORGANIZATION`, that organization is used.
- **Organization owner or `VIEW_ORGANIZATION_AUDIT`:** always their own organization, `ORGANIZATION` scope only. Asking for `SYSTEM` gives 403.
- Nobody except the super admin can delete audit rows. Each delete is itself recorded as `AUDIT_ROWS_DELETED` in the system scope.

## Actions written

| Entity | Actions |
|--------|---------|
| Offer, coupon | `CREATE`, `UPDATE`, `ENABLE`, `DISABLE`, `DELETE` |
| Waste | `WASTE_CREATED` (entity id = waste id, target = `#id`) |
| Unpaid orders | `UNPAID_ORDER_DELETED` (entity id = order id), `UNPAID_ORDERS_DELETED` (target = number of orders, details = how they were chosen) |
| User profile | `PROFILE_UPDATED`, `AVATAR_UPDATED`, `AVATAR_REMOVED` (entity type `USER`; by an admin, field names only) |
| Role | `ROLE_CREATED`, `ROLE_UPDATED`, `ROLE_DELETED`, `ROLE_ASSIGNED`, `ROLE_UNASSIGNED` |
| System | see `docs/system-configuration.md` |

Changes to a built-in role are shared by every organization, so they are recorded in the `SYSTEM` scope.

## Cleanup

Old rows are removed by the daily cleanup; the days are the system setting `Cleanup_AuditLog_Days` (default 14, minimum 14, 0 = off).
