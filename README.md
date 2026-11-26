# Waha — Backend

Spring Boot REST API for Waha, a multi-store retail and self-service kiosk system. Consumed by the [Flutter frontend](https://github.com/mohammad_mousa79/waha_platform).

## Stack

- **Java 21 + Spring Boot** — REST API, Server-Sent Events
- **MySQL 8 + Flyway** — schema migrations in `db/migrations`, stored procedures
- **JDBC only** — `JdbcTemplate` / `NamedParameterJdbcTemplate`, no JPA
- **Docker Compose** — single-command local environment

## Running locally

```bash
docker compose up --build -d
```

MySQL starts, Flyway runs all migrations automatically. API is available at `http://localhost:8081` (the container listens on 8080).

Initial data is loaded by `V2__seed.sql`.

---

## Project structure

```
waha/
├── db/
│   ├── migrations/       Flyway migrations (V##__ prefix)
│   └── stored-procs/     Repeatable stored procedures and triggers (R__ prefix)
├── deployment/           Deploy, rollback and database backup scripts
├── docs/                 Documentation (start at docs/README.md)
├── nginx/                HTTPS configuration for the server
└── src/main/java/com/waha/   One package per area: auth, product, order, payment,
                              sync, offer, coupon, inventory, integration, ...
```

---

## API

Start at [docs/README.md](docs/README.md). The calls a selling app needs are in [docs/api](docs/api/conventions.md); other features have their own documents.

---

## Auth model

Protected calls need `Authorization: Bearer <token>` (a random token, not JWT). Accounts, POS employees and kiosk devices sign in separately: [docs/api/authentication.md](docs/api/authentication.md). Roles and permissions: [docs/roles-permissions.md](docs/roles-permissions.md).

---

## Organizations and stores

Each store belongs to an organization and can belong to a branch group. Products and categories belong to the organization, so all its stores share them. Roles are given at organization, branch group or branch scope.

---

## Design notes

**Order IDs are client-generated UUIDs.** The Flutter app mints a UUID v7 the moment "Checkout" is tapped — not an auto-increment integer. This lets the kiosk create a final order ID while offline, makes `POST /api/orders` naturally idempotent (same UUID = safe no-op), and makes the ID unguessable enough to double as an access credential for `GET /api/orders/{id}`.

**Server-side pricing always.** The client sends `productId + quantity` only. `OrderService` looks up the price from the `products` table — the client's quoted price is never trusted.

**Snapshots on orders.** `currency` and `tax_rate` are copied onto the order at creation time so historical totals stay accurate if store config changes later.

**Content-addressed resource storage.** Uploaded files are stored by SHA-256 hash. Duplicate uploads share the underlying blob. Named assets (the resource library) sit on top as a directory/name mapping into the hash store.

---

## Documentation

See [docs/README.md](docs/README.md).
