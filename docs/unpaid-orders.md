# Unpaid orders

Orders that were never paid are useless once they are old. They can be deleted by hand, or removed by the daily cleanup.

## What counts as deletable

An order is deletable when all of these are true:
- its status is not `PAID` (`CREATED`, `PENDING` or `CANCELLED`);
- it has no payment with outcome `PAID`;
- it has no payment attempt still in progress (not expired yet).

A paid order is never deleted, whatever else is true.

## What is deleted with the order

Items, status history, payment attempts, failed or pending payments, offer applications and coupon records. Nothing is kept.
The order numbers per branch will have gaps where unpaid orders were removed.

## By hand (permission `DELETE_UNPAID_ORDERS`)

Given to the organization owner by default; the owner can give it to other roles. The super admin works inside an organization with `X-Organization-Id`.
The permission is checked for the whole organization, and only orders of that organization can be deleted.

| Call | Meaning |
|------|---------|
| `GET /api/admin/orders/unpaid/count?olderThanDays=30&branchId=` | `{ "count": n }`: how many a delete by age would remove (`olderThanDays` 1 to 3650; `branchId` optional) |
| `DELETE /api/admin/orders/unpaid/{id}` | Deletes one. `200 { "deleted": 1 }`, or `409` when it does not exist, is paid, or has a payment in progress |
| `POST /api/admin/orders/unpaid/delete` with `{ "ids": [...] }` | Deletes the listed orders (up to 1000). Returns `{ "deleted": n, "skipped": m }`; skipped ones were paid, in progress or not found |
| `POST /api/admin/orders/unpaid/delete` with `{ "olderThanDays": 30, "branchId": 5 }` | Deletes every deletable unpaid order older than N days (`branchId` optional). Returns `{ "deleted": n, "skipped": 0 }` |

Send either `ids` or `olderThanDays`, not both. Each delete is recorded in the audit history (type `ORDER`).

## Daily cleanup

The task `unpaid_orders` of the data cleanup (setting `Cleanup_UnpaidOrders_Days`) deletes deletable unpaid orders older than N days, for all
organizations, in batches of 500. It is **off by default** (0). When turned on the value must be 15 to 3650 days; 30 is a good start. The 15-day minimum
keeps an order that an offline kiosk may still be about to send. It is a system setting, changed by the super admin only. See `docs/system-configuration.md`.
