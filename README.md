# Waha — Kiosk Backend (Phase 1)

Spring Boot backend for Waha, a self-service kiosk system. This is a **new,
separate project** — not a module of `commerce-platform` — built to be
consumed by a **Flutter** kiosk app, not a web storefront.

Scope is deliberately Phase 1 only, per the product doc: prove the core
transaction — `Landing → Scan → Cart → Checkout → Simulated Payment →
Success` — nothing more. No accounts, no digital catalog, no real payment,
no offline sync, no fleet management. Those are later phases; see
[Roadmap](#roadmap) below.

## What's reused from `commerce-platform`, and what isn't

This wasn't built from zero. The parts of `commerce-platform` that solve the
same underlying problem were ported and adapted; the parts that solve a
*different* problem were deliberately left behind rather than forced to fit.

**Reused / adapted directly:**
- **Idempotent order creation** — `sp_create_order_idempotent` is the same
  pattern (same idempotency_key called twice returns the existing order,
  enforced at the DB layer via a unique key), with `customer_id` removed.
- **Optimistic-concurrency status transition** — `sp_mark_order_paid` uses
  the same `SELECT ... FOR UPDATE` + `version` guard as
  `sp_advance_order_status`, simplified to Waha's two-state lifecycle
  (`CREATED → PAID`) instead of the four-state review pipeline.
- **JDBC repository pattern** — plain `JdbcTemplate` / `SimpleJdbcCall` /
  `SimpleJdbcInsert`, no JPA. `OrderRepository` and `ProductRepository`
  mirror the original's structure closely.
- **Server-side pricing** — `OrderService.computeTotals()` is the same
  principle as the original: the client sends `productId + quantity` only,
  price and tax are always computed server-side from the `products` table,
  never trusted from the caller.
- **Flyway migrations + versioned/repeatable stored procs**, Maven layout,
  and the Docker multi-stage build pattern.

**Deliberately not reused:**
- **The storefront frontend.** It's a browse-first Next.js web app; Waha's
  frontend is a scan-first Flutter kiosk app with a completely different
  interaction model (no login, no browsing, idle-reset, on-screen payment
  countdown). Building kiosk UI on top of that structure would fight it more
  than it would save time.
- **Auth / customer accounts / sessions.** Phase 1's physical flow has no
  accounts at all — see `CorsConfig.java` for the note on why there's no
  `AuthInterceptor` here.
- **`payment-service`'s `PaymentProvider` interface, as-is.** It models a
  browser-redirect-to-hosted-checkout-page flow (Stripe Checkout /
  MyFatoorah Hosted Payment Page) — correct for a web storefront, wrong
  shape for a card-present kiosk terminal. Waha's `PaymentProvider` interface
  keeps the same *idea* (one seam, swap the implementation, `OrderService`
  never changes) but is shaped as a single synchronous attempt instead of a
  session+redirect. See `payment/PaymentProvider.java` for the reasoning.
  When Phase 3 adds a real terminal integration (e.g. Stripe Terminal for
  card-present payments), it implements this interface — it won't be a
  drop-in of the existing Stripe/MyFatoorah provider classes, because those
  solve checkout-redirect, not card-present.

## Design note: why order ids are UUIDs, not auto-increment

`orders.id` is a `CHAR(36)` UUID (v4), generated **client-side** by the
Flutter app the moment "Checkout" is tapped — not a MySQL auto-increment
integer, and not server-generated either. This single field does three jobs
that would otherwise need three separate mechanisms:

1. **Offline-safe identity.** Phase 4 needs the kiosk to create a real,
   final order id while disconnected from the backend entirely. An
   auto-increment integer can't do that — it requires a round-trip to get
   assigned. A client-minted UUID can, and it's still the order's real id
   once connectivity returns and the order syncs.
2. **Idempotency key.** Calling `POST /api/orders` twice with the same id is
   a safe no-op (see `sp_create_order_idempotent`) — the same mechanism that
   makes a flaky-connection retry safe today also makes an offline-sync
   replay safe later, with no separate field needed.
3. **Access credential.** A UUID v4 has enough entropy that it isn't
   guessable, so knowing one order's id doesn't let you enumerate others.
   `GET /api/orders/{id}` needs no separate auth token as a result.

Everything that isn't created on a kiosk while offline — `stores`,
`products`, `order_items`, `payment_attempts`, `order_status_history` —
keeps ordinary auto-increment integer PKs. Only the entity a disconnected
kiosk needs to originate itself gets a client-generated identity.

## Design note: currency and VAT live on `stores`, not `orders`

`stores.currency` and `stores.vat_rate` are the configured values for a
given store — the source of truth for computing *new* orders. Each order
still **snapshots** `currency` and `tax_rate` onto itself at creation time,
rather than just referencing the store: if a store's VAT rate changes later,
past orders stay provably accurate to what was actually charged, instead of
silently reinterpreting historical totals under a new rate.

Per-product VAT override (zero-rated items, etc.) is deferred — every
product currently inherits the store's rate uniformly. Adding a nullable
override column to `products` later is a one-line, zero-risk migration
whenever that's actually needed; not worth building until there's a real
product that needs it.

## Project structure

```
waha/
├── pom.xml
├── docker-compose.yml
├── Dockerfile
├── db/
│   ├── migrations/
│   │   ├── V1__core_schema.sql          stores, products, orders (UUID pk),
│   │   │                                 order_items, order_status_history,
│   │   │                                 payment_attempts
│   │   └── V2__seed_demo_products.sql    demo store + barcodes for immediate testing
│   └── stored-procs/
│       ├── R__create_order_idempotent.sql
│       └── R__mark_order_paid.sql
└── src/main/java/com/waha/
    ├── WahaApplication.java
    ├── config/CorsConfig.java
    ├── common/ErrorResponse.java
    ├── store/                            StoreConfig, StoreRepository
    │                                      (currency + VAT rate lookup)
    ├── product/                          Product, ProductRepository,
    │                                      ProductController (barcode lookup)
    ├── order/                            OrderController, OrderService,
    │   └── dto/                          OrderRepository + request/response DTOs
    └── payment/                          PaymentProvider interface +
                                           SimulatedPaymentProvider (Phase 1)
```

## Running locally

```bash
docker compose up --build
```

MySQL starts, Flyway runs migrations + stored procs automatically, and the
API is up on `http://localhost:8080`. Seed products are pre-loaded, so the
scan endpoint is testable immediately (see barcodes in
`V2__seed_demo_products.sql`).

**Note:** I wasn't able to run `mvn package` in this sandbox — it has no
network access to Maven Central, only a small allowlist of other domains.
The code closely mirrors patterns already proven working in
`commerce-platform` (same JDBC/stored-proc approach, same Spring Boot
version), but you should run a local build as your first step before relying
on it.

## API reference

No authentication anywhere — matches Phase 1's no-accounts physical flow.

### `GET /api/products/barcode/{barcode}`
Called on every scan. Validates the barcode is recognized and the product is
active — **does not check inventory quantity** (see product doc, section 4:
a scanned, physically-held item is sufficient evidence it can be sold).

```
200 → {"id":1,"barcode":"6221031000015","name":"Bottled Water 600ml","price":8.00,"active":true}
404 → {"message":"No product found for barcode 0000000000000"}
409 → {"message":"Product is not currently sellable"}
```

### `POST /api/orders/quote`
Recomputed server-side from real prices every time — call after every
scan/quantity change to show a live total.

```json
// Request
{"items":[{"productId":1,"quantity":2},{"productId":3,"quantity":1}]}

// 200 Response
{"subtotal":36.00,"tax":5.04,"total":41.04,"items":[
  {"productId":1,"name":"Bottled Water 600ml","quantity":2,"unitPrice":8.00,"lineTotal":16.00},
  {"productId":3,"name":"Potato Chips 40g","quantity":1,"unitPrice":20.00,"lineTotal":20.00}
]}
```

### `POST /api/orders`
Creates the order (status `CREATED`). **`orderId` is generated client-side**
— a UUID v4 the Flutter app mints the moment "Checkout" is tapped, online or
offline. Calling this twice with the same `orderId` is a safe no-op
(`wasCreated:false`, existing order returned), which is what makes a
flaky-connection retry — and later, an offline-sync replay — safe.

```json
// Request
{"orderId":"a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c","items":[{"productId":1,"quantity":2}]}

// 200 Response
{"orderId":"a1b2c3d4-e5f6-47a8-9b0c-1d2e3f4a5b6c","wasCreated":true,"status":"CREATED",
 "subtotal":16.00,"tax":2.24,"total":18.24,"taxRate":0.1400,"currency":"EGP",
 "paymentReference":null,"items":[...]}
```

### `POST /api/orders/{id}/pay`
Phase 1 simulated payment. `simulateOutcome` is optional — omit or send
`"SUCCESS"` for the normal path, send `"FAIL"` to exercise the decline UI.
`{id}` is the UUID from the create response.

```json
// Request
{"simulateOutcome":"SUCCESS"}

// 200 Response (paid)
{"paid":true,"status":"PAID","detail":null,"order":{"orderId":"a1b2c3d4-...","status":"PAID","paymentReference":"SIM-...","...":"..."}}

// 200 Response (simulated decline — still HTTP 200, this is a normal outcome)
{"paid":false,"status":"FAILED","detail":"Simulated decline","order":{"...":"status still CREATED"}}

// 409 — order already paid, or a concurrent /pay call won the race
{"message":"Order a1b2c3d4-... is already paid"}
```

### `GET /api/orders/{id}`
Order detail, for the success screen. `{id}` is the same UUID — unguessable,
so no separate access token is needed to look it up.

## Flutter integration notes

- **Base URL for local dev:** Android emulator → `http://10.0.2.2:8080`.
  iOS simulator → `http://localhost:8080`. A real kiosk device on the same
  network as the backend → the machine's LAN IP.
- **Cart state lives entirely in the Flutter app**, not on the server —
  there's no server-side kiosk-session concept in Phase 1. This is
  deliberate: it matches the idle-reset design (go to home screen, cart is
  just gone, nothing to clean up server-side) and keeps Phase 1 as small as
  the product doc asks for.
- **Order id**: generate a UUID (e.g. Dart's `uuid` package) once when the
  customer taps "Checkout" — this becomes the order's real, permanent id.
  Reuse the same value if you need to retry the `POST /api/orders` call, and
  hang onto it (in memory, per the no-server-session design above) for the
  `/pay` and `GET` calls that follow in the same flow.
- **The pay countdown/extend/new-order behavior** (invoice-QR-adjacent
  timing you designed) is entirely client-side too — nothing here needs to
  know about it in Phase 1, since there's no invoice QR yet (that's Phase 2,
  digital fulfillment).

## Roadmap

Everything below is **intentionally not built yet** — flagging it so it
reads as deferred scope, not an oversight:

- **Phase 2** — digital product catalog, browse/select flow, invoice QR
  generation + time-boxed display with extend/new-order controls, phone
  number collection (conditional, only when fulfillment needs it).
- **Phase 3** — real payment (a card-present terminal SDK implementing
  `PaymentProvider`), promotions/coupons/membership, conditional customer
  info.
- **Phase 4** — offline-first: local transaction queue, retry/reconciliation.
  The UUID-based idempotent identity this needs is already in place (see
  the design note above) — what's missing is the local queue itself and the
  sync/retry logic on the Flutter side.
- **Phase 5** — kiosk fleet management: device registration, heartbeat,
  remote diagnostics/commands, config, versioning.
