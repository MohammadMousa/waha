# Odoo Integration

Waha connects to Odoo via **XML-RPC** to pull the product catalog (categories and products, including images) and push confirmed orders. The integration is one-way for catalog data (Odoo → Waha) and one-way for orders (Waha → Odoo). No data flows from Waha back to Odoo's catalog.

---

## Configuration

### Required credentials

| Field       | Description |
|-------------|-------------|
| `base_url`  | Full HTTPS URL of the Odoo instance, e.g. `https://mycompany.odoo.com` |
| `username`  | Login (email) of an Odoo user with API access |
| `api_key`   | API key generated in Odoo → Settings → Technical → API Keys |

The database name is **derived automatically** from the subdomain of `base_url`:
```
https://mycompany.odoo.com  →  db = "mycompany"
```

### Where credentials are stored

Stored in the `external_systems` table (one row, `name = 'ODOO'`). The API key is persisted as plaintext in the DB column — encrypt at the infrastructure level (KMS / secrets manager) if the threat model requires it.

### How to configure

Admin UI: **Settings → Odoo Integration** (requires `MANAGE_STORES` permission).

Or via API:
```
POST /api/admin/odoo/configure?storeId={id}
Authorization: Bearer {token}
Content-Type: application/json

{
  "baseUrl": "https://mycompany.odoo.com",
  "username": "admin@mycompany.com",
  "apiKey":   "..."
}
```

Leaving `apiKey` or `username` blank in a subsequent save **keeps the existing value** — you don't need to re-enter credentials to update just the URL.

---

## Authentication

Odoo XML-RPC uses a two-step flow:

1. `POST /xmlrpc/2/common` → `authenticate(db, login, apiKey)` → returns `uid` (integer)
2. `POST /xmlrpc/2/object` → `execute_kw(db, uid, apiKey, model, method, args, kwargs)` → returns result

Every call to `OdooClient` re-authenticates to get a fresh `uid`. This is stateless and safe — Odoo's `authenticate` is fast.

> **Why not JSON-RPC?** Odoo's JSON-RPC endpoint (`/web/dataset/call_kw`) uses `auth='user'` which requires a browser session cookie. API keys do not work there. XML-RPC is the only endpoint that accepts API key authentication on Odoo SaaS.

---

## Database schema

### `external_systems`

One row per external system.

| Column                   | Type          | Notes |
|--------------------------|---------------|-------|
| `id`                     | BIGINT PK     | |
| `name`                   | VARCHAR(50)   | Unique. Currently only `'ODOO'`. |
| `base_url`               | VARCHAR(500)  | Full HTTPS URL |
| `api_key`                | VARCHAR(500)  | Odoo API key (plaintext) |
| `username`               | VARCHAR(255)  | Odoo login (email) |
| `enabled`                | BOOLEAN       | Defaults to TRUE on upsert |
| `last_category_sync_at`  | TIMESTAMP     | NULL until first category pull |
| `last_product_sync_at`   | TIMESTAMP     | NULL until first product pull |

### `external_mappings`

Bidirectional ID map between Waha local IDs and Odoo IDs.

| Column        | Type          | Notes |
|---------------|---------------|-------|
| `system_id`   | BIGINT FK     | References `external_systems.id` |
| `entity_type` | VARCHAR(50)   | `'CATEGORY'`, `'PRODUCT'`, `'ORDER'` |
| `local_id`    | VARCHAR(50)   | Waha's local ID (numeric string or UUID) |
| `external_id` | VARCHAR(100)  | Odoo's record ID (numeric string) |
| `store_id`    | BIGINT NULL   | The store scope the mapping belongs to |

Unique constraints: `(system_id, entity_type, local_id)` and `(system_id, entity_type, external_id)` — ensures no duplicate mappings in either direction.

### `sync_queue`

Outbound async work items. Orders are enqueued here immediately after payment; a background worker processes them independently of the sale flow.

| Column        | Type        | Notes |
|---------------|-------------|-------|
| `system_id`   | BIGINT FK   | |
| `entity_type` | VARCHAR(50) | Currently `'ORDER'` |
| `entity_id`   | VARCHAR(50) | Waha order UUID |
| `operation`   | VARCHAR(20) | Currently `'CREATE'` |
| `payload`     | JSON        | Snapshot of the full `OrderResponse` at time of payment |
| `status`      | VARCHAR(20) | `PENDING` → `DONE` or `FAILED` |
| `attempts`    | INT         | Incremented on each failed attempt |
| `last_error`  | TEXT        | Last error message from Odoo or the HTTP call |
| `store_id`    | BIGINT NULL | Store that originated the order |

---

## Catalog sync

### Categories

Pulled from Odoo's `product.category` model. Fields fetched: `id`, `name`, `complete_name`, `parent_id`, `write_date`.

Each Odoo category is upserted into Waha's `categories` table:
- **New**: inserted with `scope_store_id` set to the requesting store, then recorded in `external_mappings`.
- **Existing**: `name` and `key` updated in place.

The `key` column (used for deduplication) is derived as `slugify(name) + "_" + odooId`.

### Products

Pulled from Odoo's `product.template` model. Fields fetched: `id`, `name`, `list_price`, `categ_id`, `barcode`, `active`, `write_date`, `image_512`.

Each Odoo product is upserted into Waha's `products` table:
- **New**: inserted with `scope_store_id` and `scope_store_type` (looked up from the store record) to satisfy the products scope CHECK constraint. Barcode falls back to `ODOO_{id}` if Odoo returns no barcode.
- **Existing**: `name`, `price`, `category_id`, and `active` updated.

#### Category mapping

`categ_id` is a Many2one field. The Odoo category must have been pulled first for the mapping to resolve. If the category has no mapping in `external_mappings`, `category_id` is set to NULL on the product.

#### Image handling

`image_512` returns a base64-encoded PNG (512×512 px) when the product has an image, or `false` when it doesn't. When an image is present:

1. Base64 decoded → raw bytes
2. SHA-256 computed for deduplication
3. If bytes already exist in `resources` (same SHA-256) → existing `resource_id` reused
4. Otherwise → stored in `resources` + `resource_data`
5. `products.image_resource_id` set to the resource ID

### Incremental sync

The first pull fetches all records (`write_date` filter not applied). Every subsequent pull sends:
```
[["write_date", ">", "2024-01-01 10:00:00"]]
```
using the stored `last_category_sync_at` / `last_product_sync_at` timestamp. Only records modified in Odoo since the last pull are returned.

`last_*_sync_at` is updated **only when at least one record is processed** — an empty pull (nothing changed in Odoo) does not advance the timestamp.

### Triggering a pull

Via the Odoo Integration admin screen, or API:

```
POST /api/admin/odoo/pull/categories?storeId={id}
POST /api/admin/odoo/pull/products?storeId={id}
Authorization: Bearer {token}
```

Response:
```json
{ "pulled": 7, "entityType": "CATEGORY" }
```

---

## Order push

### Flow

1. Customer pays → `OrderService` marks order `PAID` → publishes `OrderPaidEvent`
2. `OdooOrderSyncService.onOrderPaid()` catches the event and **immediately enqueues** the order into `sync_queue` (status `PENDING`). The sale is never blocked on Odoo availability.
3. A `@Scheduled` worker runs every **60 seconds**, picks up to 10 PENDING items, and pushes each as a `sale.order` in Odoo.

### What gets pushed

For each order line:
- `product_id` — resolved via `external_mappings`; omitted for products not yet mapped to Odoo
- `product_uom_qty` — item quantity
- `price_unit` — unit price at time of sale
- `name` — product name in English

Order-level fields:
- `client_order_ref` — set to Waha's order UUID (used for idempotency)
- `partner_id` — resolved by looking up the Odoo user's own partner record via `res.users`. Cached in memory per service instance restart. Falls back to partner id `3` if the lookup fails.

### Idempotency

Before creating, the worker searches Odoo for `sale.order` records with `client_order_ref = orderId`. If found, the existing Odoo ID is recorded in `external_mappings` and the item is marked DONE without creating a duplicate.

### Retry logic

Exponential backoff: an item with `attempts = N` is not retried until `updated_at + 30 × 2^N seconds` has elapsed. An item that reaches `MAX_ATTEMPTS = 5` is permanently marked `FAILED` and will not be retried automatically.

To retry failed items manually, reset them in the DB:
```sql
UPDATE sync_queue SET status = 'PENDING', attempts = 0, last_error = NULL
WHERE status = 'FAILED' AND system_id = (SELECT id FROM external_systems WHERE name = 'ODOO');
```

### Manual push

The Odoo Integration screen shows a **"Push Now"** button alongside the queue stats (Pending / Failed / Done). This triggers the same worker logic on demand without waiting for the 60-second tick.

API:
```
POST /api/admin/odoo/push/orders?storeId={id}
Authorization: Bearer {token}
```
Response:
```json
{ "pushed": 2 }
```

---

## API endpoints summary

All endpoints require `MANAGE_STORES` permission.

| Method | Path | Description |
|--------|------|-------------|
| `GET`  | `/api/admin/odoo/status` | Connection status, last sync times, queue stats |
| `POST` | `/api/admin/odoo/configure` | Save/update connection credentials |
| `POST` | `/api/admin/odoo/pull/categories` | Pull categories from Odoo |
| `POST` | `/api/admin/odoo/pull/products` | Pull products from Odoo |
| `POST` | `/api/admin/odoo/push/orders` | Push pending orders to Odoo now |

---

## Known limitations

- **Barcode not updated on re-sync.** If a product's barcode is changed in Odoo after the first pull, Waha keeps the original. Barcodes are treated as immutable after initial insert.
- **Categories must be pulled before products.** If a product references a category that hasn't been pulled yet, its `category_id` will be NULL. Run a category pull first, then products.
- **No product deletion sync.** Setting a product inactive in Odoo will update `active = false` in Waha on the next pull (it disappears from the storefront), but the row is never deleted from Waha's `products` table.
- **No order status sync.** Waha pushes orders to Odoo but does not poll Odoo for status updates (confirmed, shipped, invoiced, etc.). Odoo is treated as a write-only sink for orders.
- **No customer mapping.** All pushed orders use the API user's own partner as `partner_id`. Customer identity (name, phone) is not yet sent to Odoo.
- **XML-RPC parser is hand-rolled.** The `OdooClient` parser handles the common scalar types and Many2one fields (returns the ID, not the name). Complex nested types (One2many, Many2many) are not parsed — if new Odoo fields return those types, the field will be null in the parsed result.
