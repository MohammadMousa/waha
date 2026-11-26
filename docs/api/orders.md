# Orders, payments and invoices

Prices, discounts and tax are always calculated by the server. The client sends product ids and quantities, and shows the amounts, `currency` and `taxRate` it gets back.
`storeId` and `username` are optional when a session provides them (a value sent in the body wins). Without a session, send both. A quote works without `username`.

Product ids must come from the same store as the order: keep a cart tied to one store, and clear or re-resolve it when the store changes.

## `POST /api/orders/quote`
Calculates a cart without saving it. Call it after each cart change.

```json
{"storeId": 5, "items": [{"productId": 1, "quantity": 2}], "couponCode": null, "username": null, "addressId": null}
```
Answer: `subtotal`, `tax`, `total`, `items`, `discountAmount`, `invoiceOffer`, `coupon`, `shippingOptions`.
`subtotal` is the net amount after discounts, and `total` is `subtotal + tax`. How line prices include VAT: [vat-pricing.md](../vat-pricing.md).

Each item: `productId`, `barcode`, `name`, `quantity`, `unitPrice`, `lineTotal`, `discountAmount`, `netAmount`, `taxAmount`, `offer`.
See [offers.md](../offers.md), [coupons.md](../coupons.md) and [addresses-shipping.md](../addresses-shipping.md).

## `POST /api/orders`
```json
{"orderId": "<UUID v7 made by the client>", "storeId": 5, "username": "...",
 "items": [{"productId": 1, "quantity": 2}], "couponCode": null, "shippingMethod": null, "addressId": null}
```
- The client creates `orderId`. Sending the same id again does not create a second order: it returns the saved one with `wasCreated: false`. Coupon and shipping are not re-checked on a retry, but the items are still priced first, so a retry can be refused if a product was disabled in between. In that case, read the order with `GET /api/orders/{id}`.
- `shippingMethod` and `addressId` are for online orders; kiosk and shopping mode leave them out.

Answer fields: `orderId`, `displayId`, `storeId`, `wasCreated`, `status`, `username`, `subtotal`, `tax`, `total`, `taxRate`, `currency`, `paymentReference`, `paymentMethod`, `invoiceUrl`, `createdAt`, `items`, `payments`, `discountAmount`, `invoiceOffer`, `coupon`, `offlinePushed`, `priceDiffers`, `orderReference`, `shippingMethod`, `shippingAmount`, `shippingAddress`.

| Status | Meaning (quote and create) |
|---|---|
| 400 | Missing `storeId` or `username`, or an invalid value |
| 409 | A product does not exist or is disabled (unless `scan_disabled_products` is on) |
| 422 | `{error, message}`: a coupon refusal, `SHIPPING_NOT_AVAILABLE`, or `ORDER_TOTAL_NOT_POSITIVE` |

Statuses: [order-statuses.md](../order-statuses.md). Orders made on an offline kiosk reach the server another way: [offline.md](../offline.md).

## `GET /api/orders/{id}`
The order. No sign-in: the id gives access, so treat order ids and invoice links as private. `404` if unknown.

## `GET /api/orders?storeId=&page=0&size=20`
The signed-in session's own orders (matched by username) in one store, newest first. Store: optional. `size` is at most 100. `401` without a session.
Each row: `orderId`, `displayId`, `status`, `paymentMethod`, `total`, `currency`, `invoiceUrl`, `createdAt`.

## `DELETE /api/orders/{id}`
Cancels the caller's own order by **deleting** it. Allowed while it is `CREATED` and has no unexpired payment attempt. `204` on success, `409` if not allowed, `401` without a session.

## Payment

Which methods a store offers, and which flow each uses: [payment-methods.md](../payment-methods.md). An order can be paid once. A client shows "paid" only when the server says the order is `PAID`.

### Online: `POST /api/orders/{id}/payment-session`
```json
{"provider": "stripe", "providerMode": "QR_LINK", "successUrl": null, "cancelUrl": null}
```
`provider` is `stripe` or `myfatoorah` (a `_qr` ending is accepted). Return URLs default to the invoice page.

Answer: `redirectUrl`, `qrCodeDataUri`, `expiresAt`. The last two are set only when `providerMode` is `QR_LINK` (link life: 15 minutes). Never build your own card form: the customer pays on the provider's page.

Errors: `400` missing or unknown provider, `404` unknown order, `409` already paid.

**Returning from the provider does not prove payment.** Confirmation can arrive a few seconds later, so keep reading `GET /api/orders/{id}` or listen to:

`GET /api/orders/{id}/payment-events`: a server-sent events stream that sends `payment_confirmed` with data `PAID`, then closes. It lasts up to 15 minutes, and only the newest listener per order is kept. Also check the order itself, in case the event was missed.

### Card terminal
These calls report what a card reader did, so **only a kiosk device sign-in may use them**. No sign-in is `401`; an account or employee sign-in is `403`.

The server also compares the order's store and organization with the device record. On create, confirm, cancel and late approval a difference is **only logged** for now. Reading an attempt and the pending call are limited to the device's own store.

| Call | Purpose |
|---|---|
| `POST /api/orders/{orderId}/terminal-session` | Starts a `PENDING` attempt that expires after 90 seconds. Answer: `id`, `orderId`, `amount`, `currency`, `status`. `409` if already paid |
| `GET /api/terminal-sessions/{id}` | Reads the attempt. `404` if unknown or of another store |
| `GET /api/terminal-sessions/pending` | The oldest `PENDING` terminal attempt of the device's store, or `204` |
| `POST /api/terminal-sessions/{id}/confirm` | `{"authCode": "...", "notes": {}}`. Records the approval and marks the order paid. `400` if not pending |
| `POST /api/terminal-sessions/{id}/cancel` | Cancels a pending attempt |
| `POST /api/orders/{orderId}/late-approval` | The terminal approved after the attempt ended. Body: `amount`, `rrn` (required), `approvalCode`, `terminalId`. Answer `{"result": "PAID"}` or `{"result": "IGNORED"}` |

A late approval is accepted only for an unpaid order of the caller's organization, with the exact total, within `late_approval_expiration_minutes` of order creation (default 3, allowed 1 to 60).

Attempt statuses: `PENDING`, `CONFIRMED`, `CANCELLED`, `TIMEOUT`.

### Simulated: `POST /api/orders/{id}/pay`
**A development tool that makes a fake payment, for test setups only. Do not use it for real sales.** It works only when both are true, otherwise `403`:
1. The server has `WAHA_SIMULATED_PAYMENT_ENABLED=true` (off by default).
2. A simulated payment method is on globally and not switched off for the order's store. The app mode is not checked.

No sign-in is needed. The body is required: `{}` or `{"simulateOutcome": "SUCCESS"}` succeeds, `"FAIL"` declines. Answer: `paid`, `status`, `detail`, `order`. `404` unknown order, `409` already paid.

### Provider webhooks
`POST /api/payments/webhooks/stripe` and `POST /api/payments/webhooks/myfatoorah` are called by the providers, never by apps.

## Invoices

| Call | Answer |
|---|---|
| `GET /api/invoices/{orderId}?lang=en` | HTML page. `lang=ar` for Arabic; `ref` chooses which order number is shown (`display`, `uuid`, default both) |
| `GET /api/invoices/{orderId}/pdf?lang=en` | PDF file |

No sign-in. For a QR code, use the order's `invoiceUrl` as it is. The invoice page only shows the order; it has no pay button.
A kiosk adds `offline=1` to the link of an order made without the server: until the order arrives, the page says it is being prepared and reloads, for up to 30 minutes.
