# Waha — Backend

Spring Boot REST API for Waha, a multi-store retail and self-service kiosk system. Consumed by the [Flutter frontend](https://github.com/mohammad_mousa79/waha_platform).

## Stack

- **Java 21 + Spring Boot** — REST API, Server-Sent Events
- **MySQL 8 + Flyway** — schema migrations (V1–V26), stored procedures
- **JDBC only** — `JdbcTemplate` / `NamedParameterJdbcTemplate`, no JPA
- **Docker Compose** — single-command local environment

## Running locally

```bash
docker compose up --build -d
```

MySQL starts, Flyway runs all migrations automatically. API is available at `http://localhost:8080`.

Seed products and a demo store are pre-loaded (`V2__seed_demo_products.sql`).

---

## Project structure

```
waha/
├── db/
│   ├── migrations/          V1–V26 Flyway migrations
│   └── stored-procs/        Repeatable stored procedures (R__ prefix)
└── src/main/java/com/waha/
    ├── auth/                 JWT auth, sessions, roles, permissions
    ├── category/             Product categories (admin + public)
    ├── common/               Shared exceptions, error response
    ├── config/               System properties, public base URL
    ├── integration/          Odoo sync — catalog pull, order push
    │   └── odoo/
    ├── invoice/              HTML + PDF invoice, receipt info
    ├── landing/              Landing page asset resolution
    ├── order/                Order lifecycle, quote, admin views
    ├── payment/              Payment providers, QR session, SSE, terminal
    │   ├── method/           Payment method config per store
    │   ├── myfatoorah/       MyFatoorah webhook + redirect provider
    │   ├── stripe/           Stripe webhook + checkout provider
    │   └── terminal/         Card-present terminal session flow
    ├── product/              Product catalog — browse, search, sync, admin
    ├── resource/             File storage — directories, assets, named resources
    └── store/                Store hierarchy — config, admin CRUD, scope chain
```

---

## API reference

### Authentication — `/api/auth`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/auth/register` | Register a new user |
| `POST` | `/api/auth/login` | Login with username + password |
| `POST` | `/api/auth/guest` | Create a guest session |
| `POST` | `/api/auth/logout` | Invalidate token |
| `GET`  | `/api/auth/me` | Current session + permissions |
| `POST` | `/api/auth/store` | Switch active store for the session |

### Stores — `/api/stores`

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/stores`         | List public stores (store picker) |
| `GET`    | `/api/stores/admin`   | List all stores in admin's subtree |
| `POST`   | `/api/stores`         | Create store — requires `MANAGE_STORES` |
| `GET`    | `/api/stores/{id}/admin` | Store detail — requires `MANAGE_STORES` |
| `PATCH`  | `/api/stores/{id}`    | Edit store — requires `MANAGE_STORES` |

### Products — `/api/products`

| Method | Path | Description |
|--------|------|-------------|
| `GET`    | `/api/products`              | Browse catalog (store-scoped, paginated) |
| `GET`    | `/api/products/{id}`         | Product detail — includes gallery + tags |
| `GET`    | `/api/products/barcode/{bc}` | Barcode lookup (kiosk scan) |
| `GET`    | `/api/products/search`       | Full-text search — name and tags |
| `GET`    | `/api/products/sync`         | Delta sync for offline catalog cache |
| `PATCH`  | `/api/products/{id}`         | Edit product — requires `EDIT_PRODUCTS` |
| `POST`   | `/api/products/{id}/images`  | Add gallery image |
| `DELETE` | `/api/products/{id}/images/{resourceId}` | Remove gallery image |

### Categories — `/api/categories`

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/api/categories`     | List categories for store |
| `PATCH` | `/api/categories/{id}` | Edit category — requires `MANAGE_CATEGORIES` |

### Orders — `/api/orders`

| Method | Path | Description |
|--------|------|-------------|
| `POST`   | `/api/orders/quote`   | Compute price total (no order created) |
| `POST`   | `/api/orders`         | Create order (idempotent — UUID key) |
| `GET`    | `/api/orders/{id}`    | Order detail |
| `GET`    | `/api/orders`         | Order list — requires `VIEW_ALL_ORDERS` |
| `DELETE` | `/api/orders/{id}`    | Cancel order |
| `POST`   | `/api/orders/{id}/pay` | Trigger payment attempt |
| `POST`   | `/api/orders/{id}/payment-session` | Start QR / redirect payment session |
| `GET`    | `/api/orders/{id}/payment-events`  | SSE stream — live payment status |

### Terminal — `/api`

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/orders/{id}/terminal-session`   | Start card-present session |
| `GET`  | `/api/terminal-sessions/{id}`         | Session status |
| `GET`  | `/api/terminal-sessions/pending`      | Pending session for device |
| `POST` | `/api/terminal-sessions/{id}/confirm` | Confirm payment (with EMV + auth code) |
| `POST` | `/api/terminal-sessions/{id}/cancel`  | Cancel session |

### Payment methods — `/api/payment-methods`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/payment-methods`        | Available methods for store |
| `GET` | `/api/payment-methods/admin`  | All methods with store-level toggle state |
| `PUT` | `/api/payment-methods/{id}/store-active` | Enable/disable for store |

### Invoices — `/api/invoices`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/invoices/{orderId}`      | Bilingual HTML invoice (AR/EN) |
| `GET` | `/api/invoices/{orderId}/pdf`  | PDF invoice download |

### Receipt info — `/api/receipt-info`

| Method | Path | Description |
|--------|------|-------------|
| `GET`   | `/api/receipt-info` | Store receipt configuration |
| `PATCH` | `/api/receipt-info` | Update receipt info — requires `MANAGE_STORES` |

### Resources — `/api/resources`

| Method | Path | Description |
|--------|------|-------------|
| `POST`   | `/api/resources` (multipart) | Upload resource (content-addressed) |
| `GET`    | `/api/resources/{id}`        | Serve resource by ID |
| `GET`    | `/resource/{store}/{dir}/{name}` | Serve named public resource |
| `GET`    | `/api/resources/{store}/directories` | List directories |
| `POST`   | `/api/resources/{store}/directories` | Create directory |
| `GET`    | `/api/resources/{store}/directories/{dir}` | List assets in directory |
| `POST`   | `/api/resources/{store}/directories/{dir}` | Upload asset to directory |
| `PATCH`  | `/api/resources/{store}/directories/{dir}/{name}/move`   | Move asset |
| `PATCH`  | `/api/resources/{store}/directories/{dir}/{name}/rename` | Rename asset |
| `DELETE` | `/api/resources/{store}/directories/{dir}/{name}` | Delete asset |

### Landing pages — `/api/landing`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/landing/{pageKey}` | Resolve HTML landing page — served from `pages/` directory of store's resource library |

### Odoo integration — `/api/admin/odoo`

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/admin/odoo/status`         | Connection status + last sync |
| `POST` | `/api/admin/odoo/configure`      | Set Odoo URL, API key, database |
| `POST` | `/api/admin/odoo/pull/categories`| Pull categories from Odoo |
| `POST` | `/api/admin/odoo/pull/products`  | Pull products + images from Odoo |
| `POST` | `/api/admin/odoo/push/orders`    | Push fulfilled orders to Odoo |

### Config — `/api/config`

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/config` | Public system config (base URL, app name, default store) |

### Webhooks

| Method | Path | Provider |
|--------|------|----------|
| `POST` | `/api/payments/webhooks/stripe`      | Stripe payment webhook |
| `POST` | `/api/payments/webhooks/myfatoorah`  | MyFatoorah payment webhook |

---

## Auth model

Every protected endpoint requires `Authorization: Bearer <token>`. Tokens are returned on `/api/auth/login`, `/api/auth/register`, and `/api/auth/guest`.

Sessions are scoped to a store — the active store is set at login or changed via `POST /api/auth/store`. Permissions are resolved by walking the store's ancestor chain and collecting roles at every node.

### Roles

| Role | Scope |
|------|-------|
| `SUPER_ADMIN` | Platform-wide — all permissions |
| `ADMIN` | Assigned per store — full catalog + store management |
| `OPERATOR` | Assigned per store — catalog + resource editing |
| `CASHIER` | Assigned per store — orders only |
| `REGISTERED` | Default after registration |
| `ANONYMOUS` | Guest session |

### Permissions

| Permission | Granted to |
|------------|------------|
| `VIEW_PRODUCTS` | Everyone |
| `EDIT_PRODUCTS` | OPERATOR and above |
| `MANAGE_CATEGORIES` | OPERATOR and above |
| `EDIT_RESOURCES` | OPERATOR and above |
| `VIEW_OWN_ORDERS` | Everyone |
| `VIEW_ORDER_HISTORY` | REGISTERED and above |
| `VIEW_ALL_ORDERS` | CASHIER and above |
| `PROCESS_ORDERS` | CASHIER and above |
| `MANAGE_USERS` | ADMIN and above |
| `MANAGE_STORES` | ADMIN and above |
| `MANAGE_SYSTEM` | SUPER_ADMIN only |

---

## Store hierarchy

Stores form a tree. A product or role assigned at a parent store cascades down to all descendants. `scope_store_id` on a product means it overrides or is specific to that node — `NULL` means global (visible everywhere). The `path` column on every store row encodes the full ancestor chain for efficient subtree queries.

---

## Design notes

**Order IDs are client-generated UUIDs.** The Flutter app mints a UUID v4 the moment "Checkout" is tapped — not an auto-increment integer. This lets the kiosk create a final order ID while offline, makes `POST /api/orders` naturally idempotent (same UUID = safe no-op), and makes the ID unguessable enough to double as an access credential for `GET /api/orders/{id}`.

**Server-side pricing always.** The client sends `productId + quantity` only. `OrderService` looks up the price from the `products` table — the client's quoted price is never trusted.

**Snapshots on orders.** `currency` and `tax_rate` are copied onto the order at creation time so historical totals stay accurate if store config changes later.

**Content-addressed resource storage.** Uploaded files are stored by SHA-256 hash. Duplicate uploads share the underlying blob. Named assets (the resource library) sit on top as a directory/name mapping into the hash store.

---

## Documentation

| Document | Notes |
|----------|-------|
| [`docs/phases.md`](docs/phases.md) | Completed phases and backlog |
| [`docs/roles-permissions.md`](docs/roles-permissions.md) | Role + permission detail |
| [`docs/odoo-integration.md`](docs/odoo-integration.md) | Odoo sync flow |
| [`docs/payment-methods.md`](docs/payment-methods.md) | Payment provider setup |
| [`docs/order-statuses.md`](docs/order-statuses.md) | Order lifecycle |
| [`docs/resource-management.md`](docs/resource-management.md) | Resource library |
| [`docs/landing-pages.md`](docs/landing-pages.md) | Landing page HTML format |
