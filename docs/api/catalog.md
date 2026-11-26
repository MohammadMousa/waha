# Catalog

All calls on this page are reads, and none requires sign-in. **Store: optional** is explained in [conventions.md](conventions.md#store-context).
The catalog belongs to the store's **organization**: stores of one organization see the same products and categories.

## `GET /api/stores`
Every public, active store, across organizations.

```json
[{"id": 5, "name": "branch1", "displayName": {"en": "Branch 1"}, "currency": "SAR",
  "imageResourceId": null, "orgSlug": "..."}]
```

## `GET /api/categories?storeId=`
Store: optional. Active, public categories in display order. Fields: `id`, `companyId`, `name`, `publicVisible`, `active`, `sortOrder`, `imageResourceId`, `key`, `updatedAt`.

## Prices
`priceWithVat` always includes VAT. In lists, search, barcode and sync answers, `price` also includes VAT unless the organization set `prices_exclude_vat`. **Product detail is different:** its `price` is the stored price without VAT. See [vat-pricing.md](../vat-pricing.md).

## Product fields
`id`, `barcode`, `name`, `description`, `price`, `active`, `companyId`, `publicListed`, `categoryId`, `imageResourceId`, `tags`, `priceWithVat`.

## `GET /api/products?storeId=&categoryId=&page=0&size=50`
Store: optional. Active, public products. `size` is at most 200.

```json
{"products": [ ... ], "page": 0, "size": 50, "hasMore": false}
```

## `GET /api/products/search?q=&storeId=&page=0&size=50`
Store: optional. `q` is required. Searches Arabic and English names and tags, among active, public products. Same answer shape as the list.

## `GET /api/products/barcode/{barcode}?storeId=`
Store: optional. For scanning. Matches any of a product's barcodes within the store's organization. The public flag does not filter this call.

| Status | Meaning |
|---|---|
| 200 | One product |
| 404 | Unknown barcode (the server records it for the missing products report) |
| 409 | The product is disabled. With the organization property `scan_disabled_products` on, it is returned instead |

## `full=1`
Add `full=1` to the list, search, barcode or detail call for `priceExVat`, `sku`, `updatedAt` and `barcodeList` (`{id, barcode, isPrimary}`).

## `GET /api/products/{id}`
No store needed, and no active or public filter. Returns the product fields plus `barcodes` (all of them) and `imageResourceIds` (gallery). `404` if unknown. See **Prices** above.

## `GET /api/products/sync?storeId=&since=`
Store: optional. The organization's products, including disabled and non-public ones.

```json
{"syncedAt": "2026-01-01T10:00:00Z", "products": [
  {"id": 1, "barcode": "...", "name": {}, "description": {}, "price": 11.50, "active": true,
   "imageResourceId": null, "updatedAt": "...", "priceWithVat": 11.50}]}
```
Leave out `since` for everything; afterwards send the previous `syncedAt`. It does not report deletions.
**This is not the current kiosk's offline sync.** That uses the feeds and the combined pull: [offline.md](../offline.md), [sync-combined-pull.md](../sync-combined-pull.md).

## Images
`imageResourceId` is served by `GET /api/resources/{id}`. See [resource-management.md](../resource-management.md).
