# Project Phases

_Last updated: 2026-11-16_

---

## Phase 1 — Core Platform ✅ Complete

| Item | Status |
|------|--------|
| Spring Boot backend (JDBC, no JPA) | ✅ Done |
| MySQL schema + Flyway migrations (V1–V29) | ✅ Done |
| Product catalog, categories, barcodes, pictures | ✅ Done |
| Order lifecycle (CREATED → PAID / CANCELLED) | ✅ Done |
| Idempotent order creation (stored proc) | ✅ Done |
| Guest + registered auth (Bearer token), employees and kiosk devices | ✅ Done |
| Roles & permissions: built-in roles, custom roles per organization, assignment to users and employees | ✅ Done |
| Organizations, branch groups, stores, per-store payment method overrides | ✅ Done |
| VAT-inclusive pricing | ✅ Done |
| Invoices (HTML and PDF, Arabic and English) | ✅ Done |
| Flutter app — Normal mode (scan, cart, checkout, invoice) | ✅ Done |
| Flutter app — Shopping mode (customer's phone) | ✅ Done |
| Flutter app — Kiosk mode (locked nav, idle guard) | ✅ Done |
| Bilingual UI (AR / EN, RTL support) | ✅ Done |

---

## Phase 2 — Redirect & QR Payments ✅ Complete

| Item | Status |
|------|--------|
| Stripe Checkout integration (REDIRECT) | ✅ Done |
| MyFatoorah hosted payment integration (REDIRECT) | ✅ Done |
| Stripe / MyFatoorah webhooks | ✅ Done |
| QR_LINK provider type — kiosk QR payment flow | ✅ Done |
| `payment_attempts` table split (hot in-flight vs. audit log) | ✅ Done |
| SSE (`PaymentSseRegistry`) for real-time kiosk confirmation | ✅ Done |
| `QrPaymentScreen` dialog with countdown + SSE auto-close | ✅ Done |
| Mobile Payment (`PAYMENT_URL`) — invoice URL as kiosk QR | ✅ Done |
| Admin panel: enable/disable payment methods per store | ✅ Done |
| Stripe `amount_too_small` — user-facing error (not 500) | ✅ Done |
| Invoice screen: Due amount + order number split | ✅ Done |
| Language toggle for kiosk / shopping modes | ✅ Done |
| App title dynamic from `system_properties.appName` | ✅ Done |
| Orders can be paid in several transactions (payments are stored per transaction) | ✅ Done |

---

## Phase 3 — Terminal / Card-Present ✅ Complete

| Item | Status |
|------|--------|
| Hardware selected: USB card terminal | ✅ Done |
| `TERMINAL` provider and terminal sessions | ✅ Done |
| Flutter terminal payment UI | ✅ Done |
| Late approval for a payment approved after the kiosk gave up | ✅ Done |

---

## Phase 4 — Offline & Sync ✅ Complete

| Item | Status |
|------|--------|
| Offline mode: data sync to the kiosk and order push back | ✅ Done |
| Removals feed (deleted rows reach the kiosk) | ✅ Done |
| Sync staggering, intervals and local cleanup settings | ✅ Done |
| Caching mode and payment-only sync | ✅ Done |
| Offline invoice marker and device order reference | ✅ Done |
| Global payment switch and richer offline data | ✅ Done |

---

## Phase 5 — Commerce ✅ Complete

| Item | Status |
|------|--------|
| Offers (auto-applied promotions, discounts on orders and invoices) | ✅ Done |
| Coupons (generation, redemptions, sales report) | ✅ Done |
| Customer addresses and shipping options for online orders | ✅ Done |
| Zero or negative total orders refused | ✅ Done |

---

## Phase 6 — Back Office ✅ Complete

| Item | Status |
|------|--------|
| Admin dashboard: KPIs, charts, orders and product-sales reports | ✅ Done |
| Inventory: visits, transfers, returns, stock (with its own reports permission) | ✅ Done |
| Inventory wastes: record, list with totals, export (import through the dashboard) | ✅ Done |
| Device status, device sessions and device logs | ✅ Done |
| Missing products report and cleanup | ✅ Done |
| Account sessions: list, disconnect, session cap | ✅ Done |
| System configuration for super admin, with its audit | ✅ Done |
| Product sheet import / export | ✅ Done |
| Deploy and rollback scripts | ✅ Done |
| Data cleanup: configurable days per table (see below) | ✅ Done |

### Data cleanup

One daily job removes old rows from a fixed list of tables. The admin chooses the number of days per table in System Configuration; 0 turns it off for that table.

| Table | Default | Minimum |
|-------|--------:|--------:|
| Expired payment attempts | 30 days | 14 days |
| Audit history (system, offers, coupons, roles) | 14 days | 14 days |
| Order status history | 14 days | 14 days |
| Integration sync queue: payload of finished rows (emptied, row kept) | 30 days | 14 days |
| Device logs | 30 days | 14 days |
| Sync removals | 180 days | 30 days |

- Orders, order items, payments, coupon redemptions, offer applications and inventory wastes are never removed.
- **Integration sync queue: never delete its rows.** The orders report decides "synced" from the finished order rows in this table, so deleting them would show old orders as not synced. The cleanup only empties the stored content of finished rows and keeps the row itself.
- Rows that are still pending or failed are never touched, however old. A failed row keeps its error until someone removes it by hand.
- Catalog pulls (about one row a day) are not cleaned; remove them by hand if wanted.
- Payment attempts: only expired ones are removed, never one in progress. Old unpaid orders may then show an empty payment type in the orders report.
- Sync removals keep a higher minimum: a kiosk offline for longer than that would never learn about removed items.
- Deletes run in small batches, and each run logs how many rows it removed.
- The admin screen shows each table with its row count, days, last run and rows removed, plus a "run now" button.

---

## Phase 7 — Integrations ✅ Complete

| Item | Status |
|------|--------|
| Odoo product and category import | ✅ Done |
| Odoo order sync | ✅ Done |
| Odoo POS push (branches and payment methods linked to Odoo) | ✅ Done |
| Integration logs in the admin | ✅ Done |

---

## If needed

| Item | Notes |
|------|-------|
| Push notifications | Not needed for payments (the kiosk and invoice update on their own). Only if another use appears. |
| Customer-facing order history polish | The orders screen exists; polish only if the client app becomes a priority. |
| Branch groups (regions) | The data model and the role scope "branch group" already work. Missing: create, rename, delete, move a branch into a group, and a dashboard screen. Build when a client has about 10 or more branches or asks for regional managers. Configuration inheritance (company, group, branch) is a separate, later idea. |

---

## Notes

- Currency stays single per store and organization, because Odoo needs one currency per company.
- Nothing is planned; the "If needed" items are optional.
