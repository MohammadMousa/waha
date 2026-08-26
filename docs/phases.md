# Project Phases

_Last updated: 2026-08-26_

---

## Phase 1 — Core Platform ✅ Complete

| Item | Status |
|------|--------|
| Spring Boot backend (JDBC, no JPA) | ✅ Done |
| MySQL schema + Flyway migrations (V1–V21) | ✅ Done |
| Product catalog, categories | ✅ Done |
| Order lifecycle (CREATED → PAID / CANCELLED) | ✅ Done |
| Idempotent order creation (stored proc) | ✅ Done |
| Guest + registered auth (Bearer token) | ✅ Done |
| Roles & permissions (ROLE_USER, STORE_ADMIN, SUPER_ADMIN) | ✅ Done |
| Store config, per-store payment method overrides | ✅ Done |
| Flutter app — Normal mode (scan, cart, checkout, invoice) | ✅ Done |
| Flutter app — Shopping mode (customer's phone) | ✅ Done |
| Flutter app — Kiosk mode (locked nav, idle guard) | ✅ Done |
| Bilingual UI (AR / EN, RTL support) | ✅ Done |
| Odoo integration (product + order sync) | ✅ Done |

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

---

## Phase 3 — Terminal / Card-Present 🔲 Planned

| Item | Status |
|------|--------|
| Hardware selection (POS terminal / Stripe Terminal reader) | 🔲 Pending hardware decision |
| `TERMINAL` provider implementation (Python daemon or SDK) | 🔲 Not started |
| `PaymentProvider` interface already in place | ✅ Interface ready |
| Flutter terminal payment UI | 🔲 Not started |

---

## Advanced Phases (Backlog)

| Item | Notes |
|------|-------|
| Partial payment tracking | `paid_amount` column on orders; Due = total − paid. Deferred — current model is all-or-nothing. |
| Weekly `payment_attempts` cleanup job | Scheduled job to delete expired rows older than 7 days |
| Product image management | Upload + serve product images |
| Customer-facing order history | Already seeded (orders screen); needs polish |
| Push notifications | Payment confirmed, order ready |
| Inventory / stock management | Out-of-scope for MVP |
| Multi-currency per order | Currently single currency per store |

---

## Notes

- Hardware for Phase 3 (card reader) is not yet selected — the `PaymentProvider` interface is shaped for synchronous card-present attempts, ready to plug in.
- Partial payment support requires a DB schema change (`paid_amount` on `orders`, modify `sp_mark_order_paid`). Tracked in `advanced_phases.md` memory.
