# Order Statuses

> **Status:** work in progress — this document will be completed when the order lifecycle is finalized.

---

## Status lifecycle

```
CREATED → PAID
              └→ (pushed to Odoo via sync_queue)
```

## Statuses

| Status    | Description |
|-----------|-------------|
| `CREATED` | Order placed, payment not yet attempted |
| `PAID`    | Payment confirmed; triggers Odoo sync event |

## Notes

- Order IDs are UUID v4, generated client-side at checkout time.
- Calling `POST /api/orders` twice with the same UUID is a safe no-op (idempotent, via `sp_create_order_idempotent`).
- An order's `status` transitions are guarded by an optimistic-concurrency stored proc (`sp_mark_order_paid`) — concurrent `/pay` calls for the same order result in exactly one winner.

---

*Full details (payment attempts, status history table, future statuses) to be documented here.*
