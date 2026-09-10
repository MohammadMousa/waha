# Inventory Management

## Overview

WAHA's inventory system tracks stock movements across branches via employee visits. It is intentionally simple in Phase 1: no complex routing, no reservation system, no negative-stock blocking. The goal is an accurate ledger of what moved, where, and when.

---

## Stock Model

Stock is stored per `(store_id, product_id)` in `store_inventory`:

```
store_inventory
  store_id    — the branch
  product_id  — the product
  quantity    — current stock level (running total)
  updated_at  — last write timestamp
```

`quantity` is the only stock figure. It is adjusted atomically by delta (`+` or `–`) on every movement. There is no separate "opening balance" column.

### Why no start_quantity?

The opening balance is handled by the first transfer, not a special field. When a branch onboards, the admin records a transfer for the quantities currently on the shelf. That transfer becomes the opening entry in the ledger. After that, all stock movements are treated the same way — there is no special-case onboarding path.

This keeps the model clean:
- `quantity` always equals the sum of all movements since the first transfer.
- Every unit in the system has a transfer record behind it.
- Reports don't need to account for a hidden offset.

### Company Stock

Company stock is not stored in the database. It is the source of all stock before it is transferred to branches. Purchase orders are managed externally (e.g. in Odoo). The system takes it for granted that the company has the stock being transferred — it does not validate against a company ledger.

For reporting purposes, "total system stock" is the sum of all branch quantities. What is still at the company (not yet transferred) is outside the system's scope in Phase 1.

---

## Stock Changes

Three operations write to `store_inventory`:

| Event | Effect |
|---|---|
| Transfer (visit completed) | Origin store `–qty`, target store `+qty` |
| Return (visit completed) | Target store `–qty`, origin store `+qty` |
| Paid sale (`OrderPaidEvent`) | Store `–qty` per order item |

All writes use `INSERT … ON DUPLICATE KEY UPDATE quantity = quantity + delta`, which is atomic and handles first-write (no existing row) cleanly.

Stock can go negative in Phase 1. There is no hard block. The business is responsible for ensuring transfers are backed by real stock.

---

## Visits

A visit is an employee's inventory activity session. It has a lifecycle:

```
ACTIVE  →  COMPLETED
        →  CANCELLED
```

| Column | Meaning |
|---|---|
| `employee_id` | Who performed the visit |
| `store_id` | The employee's origin store (set from session at visit start) |
| `organization_id` | The company |
| `created_at` | Visit start time |
| `completed_at` | Visit end time (NULL while ACTIVE) |

`completed_at − created_at` is the visit duration, available for reporting (avg duration per employee, per store, per day, etc.).

---

## Operations

Each visit can contain multiple operations. An operation is a single TRANSFER or RETURN against a target branch.

| Column | Meaning |
|---|---|
| `visit_id` | Parent visit |
| `operation_type` | `TRANSFER` or `RETURN` |
| `target_store_id` | The other branch in the movement |
| `notes` | Optional free text |
| `created_at` | When this operation was added during the visit |

An operation has one or more items (`inventory_operation_items`):

| Column | Meaning |
|---|---|
| `operation_id` | Parent operation |
| `product_id` | The product |
| `quantity` | How many units |

Items are unique per `(operation_id, product_id)` — the same product cannot appear twice in the same operation.

---

## Product Routing

Routing is **not implemented in Phase 1**.

The design intention for future phases: routing is one-to-many (company → set of branches). A product with no routing configured is unrestricted. The `route_stock` concept (quota/transit tracking per branch) is reserved for Phase 2+. The schema can accommodate it by adding a `route_stock` column to `store_inventory` and splitting `quantity` into `branch_stock` (physical) and `route_stock` (in-transit/allocated) when needed.

---

## API Reference

All inventory endpoints require an **employee session** (`Authorization: Bearer <token>` from `POST /api/pos/auth/login`) with `PROCESS_INVENTORY` permission (included in `OPERATOR` and `BRANCH_ADMIN` roles).

### Branches

```
GET /api/inventory/branches
```
Returns all active stores in the employee's organization. Used to populate the target-branch picker.

### Visits

```
POST /api/inventory/visits
```
Starts a new visit. Origin store is taken from the session. No request body.

```
GET /api/inventory/visits/{visitId}
```
Returns the full visit with all operations and items.

```
POST /api/inventory/visits/{visitId}/complete
```
Ends the visit. Validates at least one operation with at least one item exists, then atomically applies all inventory adjustments and marks the visit `COMPLETED`.

### Operations

```
POST /api/inventory/visits/{visitId}/operations
```
Adds an operation to an active visit.

Request body:
```json
{
  "operationType": "TRANSFER",
  "targetStoreId": 2,
  "notes": "optional",
  "items": [
    { "productId": 5, "quantity": 3 }
  ]
}
```

```
DELETE /api/inventory/visits/{visitId}/operations/{operationId}
```
Removes an operation (and its items) from an active visit.

### Stock

```
GET /api/inventory/stock/{storeId}
```
Returns current stock levels for a store. Requires `VIEW_INVENTORY` permission (CASHIER and above).

---

## Admin Reports API

Requires an **admin user session** with `VIEW_ALL_ORDERS` permission. All results are scoped to the session's organization. All list endpoints return `{ items, totalCount, page, size }` with `page` (0-based) and `size` (1–100, default 20).

### Visits

```
GET /api/admin/inventory/visits
```

Paginated list of employee visits. Filters: `branchId`, `employeeId`, `from`, `to`.

| Field | Notes |
|---|---|
| `visit_id` | |
| `employee_id`, `employee_name` | Full name from `first_name + last_name` |
| `branch_id`, `branch_name` | The employee's origin store for the visit |
| `status` | `ACTIVE` \| `COMPLETED` \| `CANCELLED` |
| `start_time` | `created_at` |
| `end_time` | `completed_at` — `null` while `ACTIVE` |

Duration is `end_time − start_time`, computed on the frontend.

### Transfers

```
GET /api/admin/inventory/transfers
```

One row per product per operation. Filters: `branchId`, `productId`, `employeeId`, `from`, `to`.

| Field | Notes |
|---|---|
| `date` | `inventory_operations.created_at` |
| `product_id`, `product_name` | Raw JSON string `{"ar":"...","en":"..."}` |
| `branch_id`, `branch_name`, `branch_display_name` | The target branch of the transfer |
| `quantity` | Units moved |
| `employee_id`, `employee_name` | Who performed the visit |
| `visit_id` | Parent visit |

### Returns

```
GET /api/admin/inventory/returns
```

Identical shape and filters to Transfers. Rows are `RETURN` operations only.

### Stock

```
GET /api/admin/inventory/stock
```

Filters: `scope`, `branchId`, `productId`, `categoryId`.

`scope=BRANCHES` (default) — one row per branch × product. `branchId` narrows to one branch.

| Field | Notes |
|---|---|
| `branch_id`, `branch_name`, `branch_display_name` | |
| `product_id`, `product_name` | Raw JSON string |
| `quantity` | Current stock at that branch |

`scope=COMPANY` — one row per product, summed across all org branches. `branchId` filter ignored.

| Field | Notes |
|---|---|
| `product_id`, `product_name` | Raw JSON string |
| `total_quantity` | `SUM(quantity)` across all branches |

---

## Error Responses

All errors return `{ "message": "..." }` with an appropriate HTTP status code.

Common inventory errors:

| Message | Status |
|---|---|
| `No store context in session.` | 400 |
| `Visit not found` | 400 |
| `Visit is not active (status: COMPLETED)` | 400 |
| `Visit has no operations.` | 400 |
| `operationType must be TRANSFER or RETURN` | 400 |
| `item quantity must be positive` | 400 |
| `Permission denied: PROCESS_INVENTORY` | 403 |
| `This endpoint requires an employee session` | 403 |
| `Visit does not belong to this employee` | 403 |

---

## Database Tables

```
store_inventory          — running stock per (store, product)
inventory_visits         — employee visit sessions
inventory_operations     — TRANSFER/RETURN records per visit
inventory_operation_items — products + quantities per operation
```

Migration: `V7__inventory.sql`
