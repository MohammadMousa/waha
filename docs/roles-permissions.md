# Roles & Permissions

> **Status:** work in progress — this document will be completed when the roles and permissions system is finalized.

---

## Roles

| Role ID | Name          | Scope |
|---------|---------------|-------|
| 1       | `SUPER_ADMIN` | Platform-wide |
| 2       | `ADMIN`       | Per-store |
| 3       | `REGISTERED`  | Per-store (default on registration) |

## Permissions

| Permission      | Granted to |
|-----------------|------------|
| `MANAGE_SYSTEM` | `SUPER_ADMIN` only |
| `MANAGE_STORES` | `SUPER_ADMIN`, `ADMIN` |
| *(more TBD)*    | |

## Notes

- `user_roles.store_id` is a NOT NULL FK to `stores.id` — there is no global (null) store scope at the DB level.
- Permissions are resolved by walking the store chain (current store → parents → root) and collecting all roles the user has at any node in that chain.
- The session's active store is set at login time to the system default store.

---

*Full details to be documented here.*
