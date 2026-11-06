# Offline mode, Phase 1: contracts

Backend status: everything below is built and tested on localhost:8081; nothing is on EC2 yet.
Phase 1 scope: products and other basic data are pulled to the kiosk's SQLite; paid orders are created locally and
pushed to the server. Offers, coupons, new logins, reports and mobile payment stay online only.

## 1. Properties (GET /api/config, existing public call, `orgId` optional)
Always present, defaults filled by the server. Values are strings. Admins change them through the normal organization
properties calls (`GET /api/admin/organization/properties/keys` lists them with a `default`).

| Property | Default |
|---|---|
| offline_mode | false |
| sync_pull_start_delay_seconds | 60 |
| sync_stagger_seconds | 180 |
| sync_pull_interval_minutes | 60 |
| sync_pull_patch_size | 200 |
| sync_pull_timeout_seconds | 30 |
| sync_idle_seconds | 10 |
| sync_push_interval_minutes | 3 |
| sync_push_timeout_seconds | 60 |
| sync_push_retry_max_delay_minutes | 15 |
| push_paid_orders_only | true |
| scan_disabled_products | false |
| offline_unpaid_cleanup_enabled | false |
| offline_unpaid_cleanup_days_offset | 15 |
| offline_audit_cleanup | false |

Local cleanup (kiosk side, read from `GET /api/config`):
- `offline_unpaid_cleanup_enabled` (true/false): the kiosk deletes local orders that are still unpaid (never sent) and older than
  `offline_unpaid_cleanup_days_offset` days (allowed 1 to 365). Paid orders and dead-lettered orders are never deleted by it.
- `offline_audit_cleanup` (true/false): the kiosk deletes local audit info older than the same days offset, only for orders
  already sent to the server. Unsent paid orders and dead-lettered orders keep theirs, because the device holds the only copy.

Staggering (kiosk side, so many devices do not start together after an outage, a mass reboot or the auto-restart):
- Once per app start the kiosk computes `delay = sync_pull_start_delay_seconds + (hash(deviceId) mod sync_stagger_seconds) + small random jitter`
  and keeps it until the next restart. The timer still decides when cycles happen; before a pull the kiosk checks that its delay has passed.
- `sync_stagger_seconds` is read from `GET /api/config` at app start, so a changed value applies at the next restart. Allowed 30 to 3600;
  there is no "off" value, and the kiosk clamps anything below 30 up to 30.
- Add about 10% random jitter to the pull interval (computed once per start) and about 20% to every failed-order retry. Paid orders are never delayed.
- Scheduling uses a monotonic clock; cursors use server time. A pull cancelled by customer activity resumes from its saved cursor.
- After connectivity returns, do one pull after a random 0 to 60 seconds. Honor `Retry-After` if the server ever answers 429 or 503 (it does not today).
- Send the status call only when the numbers changed, or about every 10 minutes at most.
- Not used: slot or modulo scheduling, server-side queues, cache or version tables. The server could later add a stateless limit
  (429 with `Retry-After`) or a "next pull in N seconds" hint if device status shows the server struggling.

Why these defaults (owner's notes):
- The defaults are deliberately gentle so that many devices cannot load the server without anyone noticing. A pull every 60
  minutes and a retry every 3 minutes keep the load low even with many branches.
- A new device is normally prepared and fully synced before it is deployed, so the first sync does not depend on the interval.
- A company that changes prices often can lower `sync_pull_interval_minutes` for its organization (allowed 1 to 1440); a price change
  or a disabled product then reaches kiosks sooner. The cost is more requests, so lower it only where it is needed.
- `sync_push_interval_minutes` (allowed 1 to 60) is only the retry of orders that failed or were waiting: a paid order is always
  pushed at once, and the retry delay still backs off up to `sync_push_retry_max_delay_minutes` while the server stays down.
- With the 60-minute default a change can take up to an hour (plus the idle wait) to reach a kiosk. That is accepted.

`scan_disabled_products`: when false a disabled product is refused on scan and on order creation (online today: 409
"not currently sellable"). When true it can be scanned and sold. The kiosk applies the same rule to its local scan
(inactive products are in the feeds with `active: false`).

## 2. Pull feeds (all need the device's Authorization header)
`GET /api/sync/{table}?since=<ISO instant>&afterId=<id>&size=<n>`
Tables: `products`, `barcodes`, `categories`, `payment-methods`, `store-payment-methods`, `product-images`.
Response: `{ "items": [...], "last": true|false, "next": {"since": "...", "afterId": 123} , "serverTime": "..." }`
- Rows ordered by (updatedAt, id). Inactive rows are included (`active:false`), so a deactivated product reaches the device.
- First pull: omit `since`. Following pulls: send the saved `next` of the last committed page.
- Device starts every pull 10 seconds before its saved `since` (overlap) and writes with upsert.
- Save the cursor only after the page is committed in SQLite. If a request times out or the customer becomes active, drop it; nothing is saved.
- `size` defaults to `sync_pull_patch_size`; the server caps it at 500.
- Product item: id, barcode, sku, name, description, price (excluding VAT, as stored), priceWithVat, active, public, categoryId, imageResourceId, updatedAt.
- All feeds need a session with a store; no or revoked session is `401`. Payment methods are global, store payment methods are for the session's store, the rest for the store's organization.
- Rows deleted on the server are not announced (barcode replacement by the Odoo import is the one case); no tombstones in Phase 1.
- The existing `GET /api/products/sync` stays unchanged for old kiosks.
- The kiosk must not take orders until products, barcodes and payment-methods have each completed one full pull.

## 3. Device config for starting offline (GET /api/sync/device-config)
`{ deviceId, deviceNo, storeId, organizationId, currency, vatRate, pricesIncludeVat, shortCode, invoiceBaseUrl, lastReferenceSerial }`
- No parameters: it uses the logged-in device session (403 for a non-device session). `vatRate` is a fraction (0.15).
- `deviceNo` (1..99, unique per store) is set on the dashboard device screen; if none is sent when a device is created the server
  assigns the lowest free number, and existing devices were numbered by the migration. Never null.
- `shortCode`: the store's optional prefix for order references (for example `WH`), or `null`.
- `invoiceBaseUrl`: the same public host the server puts in `invoiceUrl` (no trailing slash). The kiosk builds the invoice QR link as
  `<invoiceBaseUrl>/api/invoices/<orderId>`.
- `lastReferenceSerial`: the 3-digit serial of this device's latest order reference (0 to 999), or `null` when none. Continue from it
  after a reinstall.
- Sync status: the kiosk reports `POST /api/sync/status` `{ lastPullAt, lastPushAt, pendingOrders, failedOrders }` (all optional;
  ISO instants and counts of 0 or more; 204). Send after each pull/push attempt, at most once a minute. The dashboard shows it per device.
- Cached login: the kiosk opens with its cached session and validates it in the background. Sessions never expire.
  Only a real `401` means revoked, so sign out only then. A timeout or network error never signs out.
  Existing check: `GET /api/kiosk/auth/me`.

## 4. Order number and order reference
- The **order number** (`displayId`, "#96073") is always assigned by the server, once, when the order is pushed. The kiosk keeps it null
  while offline and stores what the push response returns. A retry returns the same number.
- The **order reference** is what a customer can quote before the order reaches the server. Shape: `<prefix>-<device no>-<serial>`,
  for example `WH-01-233`.
  - prefix: the store's `shortCode` from device-config, or the store id when it is empty. The kiosk may create or overwrite it. The
    server does not check the shape beyond: letters, digits, dot, dash, underscore, 1 to 30 characters.
  - device no: `deviceNo` as 2 digits. serial: 3 or 4 digits, chosen by the kiosk (the server stores any reference of the allowed
    shape); counts up per device and goes back to zero after the largest value (no daily reset). `lastReferenceSerial` is read from the
    trailing digits of the device's latest reference. The server's own fallback reference (only when a push has none) uses 3 digits.
  - Created by the kiosk with the order, stored with it, never changed. If a push has no reference the server makes one in the same shape.
  - Not unique (a serial comes back after 1000 orders of a device). Search uses reference plus a date range.
- Both appear in order responses (`displayId`, `orderReference`), on the invoice page and PDF (the reference is the large headline, the
  order number a smaller line), and in the admin orders list
  (`GET /api/admin/reports/orders?reference=WH-01-233`, columns `order_reference`, `offline_pushed`, `price_differs`).
- Store admin: `PATCH /api/stores/{id}` accepts `shortCode` (up to 10 letters or digits, stored upper case, empty clears it; bad value:
  400 `{message, errors{shortCode}}`); `GET /api/stores/{id}/admin` returns it.

## 5. Local pricing (no coupons or offers offline)
Per line: net = unitPrice x quantity (prices from the pull are excluding VAT, `priceWithVat` is the display price).
Tax per line = net x vatRate, rounded half-up to 2 decimals. Total = sum of net + sum of tax. Lines add up to the total.
Same rule the server uses today: subtotal = sum of line net, tax = sum of per-line tax (half-up), total = subtotal + tax.
Always show a line as net + that line's tax. Never show unit `priceWithVat` x quantity as the line total: it can differ by 0.01.

## 5b. Payment methods
Both feeds are needed. A method is offered when: `payment-methods` row is active, its `availableModes` contains KIOSK,
and `store-payment-methods` has no row for it or that row is active. Order by the store row's `sortOrder` when set, else the
global `sortOrder`. A PAYMENT_URL method also needs a non-empty `paymentUrl`. While offline, offer only methods with `offlineCapable` true.

## 5c. Invoice QR for orders not pushed yet
The QR is the same online and offline (the existing invoice URL for the order UUID). When the server has no order with a valid
UUID yet, `GET /api/invoices/{uuid}` returns 200 with a "your invoice is being prepared" page (English or Arabic with `?lang=ar`) that
refreshes itself every 10 seconds and shows the invoice once the order arrives. A badly formed id is still 404.
The JSON order call (`GET /api/orders/{id}`) keeps returning 404 for an unknown order; a web app that uses it must treat a 404
for a well-formed UUID as "waiting" and poll.

## 6. Order push: `POST /api/sync/orders`
One order per call, one database transaction. Device session required. Body:
```json
{
  "orderId": "uuid-v7",
  "orderReference": "WH-01-233",
  "storeId": 7,
  "username": "oasis.kiosk1",
  "status": "PAID",
  "currency": "SAR",
  "taxRate": 0.15,
  "subtotalAmount": 10.00,
  "taxAmount": 1.50,
  "totalAmount": 11.50,
  "createdAt": "2026-11-06T10:15:30Z",
  "items": [ { "lineNo": 1, "productId": 12, "quantity": 2, "unitPrice": 5.00 } ],
  "payments": [ { "paymentId": "uuid", "provider": "terminal", "outcome": "PAID",
                  "providerReference": "terminal-txn-ref", "createdAt": "2026-11-06T10:16:00Z",
                  "authCode": "123456", "notes": { "rrn": "...", "maskedCard": "...", "terminalId": "..." },
                  "lateApproval": false } ]
}
```
Response `200`: `{ "result": "CREATED" | "UPDATED" | "ALREADY_SYNCED", "orderId": "...", "displayId": 96073, "orderReference": "WH-01-233" }`.
- Statuses: CREATED, PENDING, PAID, CANCELLED. Only successful (PAID) payments are sent; a PAID order needs at least one. Payment attempts
  and status history are not sent. There is no payment amount field: the amount is the order total.
- The server stores the payment, plus a confirmed payment attempt carrying `authCode` and `notes` (same audit as online).
  `lateApproval: true` adds `late_approval: true` to the notes. A PAID push for an order the server already holds as CANCELLED is
  refused with `ORDER_CANCELLED_ON_SERVER` (409), so it stays in the kiosk's dead-letter list.
- Same order sent again with the same lines: `ALREADY_SYNCED`. Lines never change after creation. Order first pushed unpaid then
  paid: `UPDATED`. The paid event (stock, Odoo, dashboard) fires once, on the first change to PAID. An older copy never moves a paid or
  cancelled order backwards.
- The server does not reprice. It checks that the lines, tax and total add up (section 5), that every product exists in the store's
  organization, the currency, and the store. A price or tax rate that differs from the server's is accepted and only flagged
  (`priceDiffers`). `createdAt` more than an hour in the future is replaced by server time.
- Order responses everywhere carry `offlinePushed` and `priceDiffers` (booleans) and `orderReference`.
- Every order line has a `line_no` (1, 2, 3 ...), including orders made online; existing lines were numbered by the migration.
- A device's `last_push_at` is also refreshed by every accepted push.

Errors: `{ "error": "CODE", "message": "...", "retryable": true|false }`
- Retryable (keep PENDING, back off): `5xx`, `408`, `429`, timeouts, network errors.
- Permanent (move to dead letter, keep the order locally, keep sending other orders):
  422: `INVALID_REQUEST`, `PRODUCT_NOT_FOUND`, `TOTALS_MISMATCH`, `CURRENCY_MISMATCH`, `STORE_MISMATCH` (order store is not the device's
  store), `PAYMENT_MISSING` (PAID without a payment).
  409: `ITEMS_CONFLICT` (order exists with other lines or totals), `PAYMENT_CONFLICT` (same paymentId with other details),
  `ORDER_CANCELLED_ON_SERVER`.
- `401`/`403`: stop pushing, keep orders, show a warning. Do not mark orders failed.

## 7. Push rules (kiosk)
- A paid order is saved in SQLite first, then pushed at once. On failure it stays PENDING.
- A paid order is pushed at once; a scheduler every `sync_push_interval_minutes` retries all PENDING orders. Back off up to `sync_push_retry_max_delay_minutes`.
- Push is never paused by customer activity. Pull is, and an in-flight pull request is cancelled at once on activity.
- With `push_paid_orders_only` true, unpaid orders stay local.
- Dead-letter list in settings with a retry button.
- Show the order reference as the order number (it stays the number the customer saw); store the server's order number when the push returns it.

## 8. Not in Phase 1
Offers and coupons (online only), new logins, reports, mobile payment, resource caching, hard-delete tombstones, sync versions.
