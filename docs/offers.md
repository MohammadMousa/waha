# Offers (auto-applied promotions) — Phase 1

Source: "Waha Offers (Auto-Applied Promotions) — Phase 1 Spec" (v0.1, 2026-10-19) and the Smart Retail
Offer screens. This file describes what the backend does. Promo codes, usage limits, buy-X-get-Y,
customer segments and Odoo promotion sync are out of scope (Phase 2).

An offer is applied **automatically** at pricing time. There is no code to type and nothing for the customer to do.
The server prices every cart; the kiosk only shows what the server returns and never calculates a discount.

## Terms

| Smart Retail | Waha |
|---|---|
| Client | Organization (`organization_id`) |
| Branch | Store |
| Global offer | Invoice-level offer: discounts the whole cart |
| Product offer | Line-level offer: discounts selected products |

## Offer fields

| Field | Rules |
|---|---|
| name | Required, up to 120 characters, not unique |
| type | `GLOBAL` or `PRODUCT`. Cannot be changed after creation |
| organization | The client that owns the offer. The manager must belong to it |
| discountMode | `AMOUNT` or `PERCENTAGE` |
| discountValue | Greater than 0. Percentage: up to 100 |
| maxDiscountAmount | Optional cap, meant for percentage offers |
| startDate, endDate | Required, dates (`yyyy-MM-dd`). End is not before start. The end date includes the whole day |
| enabled | Default true |
| storeScope | `ALL` or `SOME`. With `SOME`, `storeIds` must hold at least one store, and every store must belong to the client |
| productIds | Required and non-empty for `PRODUCT`; each product must belong to the client |
| deletedAt | Soft delete. Past orders keep their discount snapshot |

Derived status (not stored): **Enabled**, **Disabled**, **Scheduled** (starts later), **Expired**.

An offer is **active** for a store when it is enabled, not deleted, the current time is within its dates,
the store is in scope and belongs to the offer's client, and (for product offers) the product is in the list.
Dates are evaluated on the server in Riyadh time (stores have no timezone field yet). The kiosk clock is never used.

## Pricing order (one place, used by quote, order creation, invoices)

1. Take each line's price from the product.
2. Pick the product offer for each line. If several match, the largest discount in money wins; ties go to the newest offer.
   - Percentage: applied to the line total.
   - Amount: per unit, times the quantity, never more than the line total.
   - `maxDiscountAmount`, if set, also limits the discount of each line.
3. Net of each line = line total − line discount. Add them for the cart subtotal after line discounts.
4. Pick the global offer. Largest discount wins; ties go to the newest.
   - Percentage: subtotal × value / 100, limited by `maxDiscountAmount` if set.
   - Amount: the value, never more than the subtotal.
5. Spread the global discount over the lines in proportion to their net amount, rounded half up to 2 decimals.
   Any leftover cent goes to the largest line, so the lines add up exactly to the invoice discount.
6. Tax is rounded per line on the net amount of that line (same rule as before), then summed.
7. Total = net subtotal + tax.

One product offer per line and one global offer per invoice. A product offer and a global offer can stack,
because the global offer works on what remains after line discounts. Offers never push a line below zero.
All money is `DECIMAL(12,2)`.

## Stored data

- `orders.subtotal_amount` is the **net** amount after all discounts, so `total = subtotal + tax` still holds.
  `orders.discount_amount` is the total discount of the order (0 for older orders).
- `order_items.discount_amount` is the line's own discount plus its share of the global one;
  `order_items.tax_amount` is the line's tax. Older lines have 0 / NULL.
- `offers.start_date` and `end_date` are dates (no time of day), matching the date pickers of the offer screens.
- `offer_audit` keeps every admin change (create, update, enable, disable, delete) with the user and the before and after values.
- `offer_applications` has one row per applied offer per order, with a copy of the offer's name, type, mode
  and value at order time and the discount actually given. Editing or deleting an offer never changes past orders.

## Admin API

All calls use the normal bearer token. Managing needs `MANAGE_OFFERS` (client manager / organization owner);
viewing needs `VIEW_OFFERS` (branch admin, operator). Every change is written to the audit log with the user and before/after values.

| Call | Purpose |
|---|---|
| `GET /api/admin/offers?status=&q=&page=&size=` | List with search, status filter, paging (size up to 500) |
| `GET /api/admin/offers/{id}` | One offer with stores, products, recent applications, total discount given |
| `POST /api/admin/offers` | Create |
| `PUT /api/admin/offers/{id}` | Edit (type cannot change) |
| `PATCH /api/admin/offers/{id}/enabled` | Enable or disable: `{"enabled": true}` |
| `DELETE /api/admin/offers/{id}` | Soft delete |
| `POST /api/admin/offers/preview` | Optional: discount for a sample cart plus warnings (amount larger than the price, overlap with another offer in the dates) |

Request body for create and edit: `name`, `type` (create only), `clientId` (optional, defaults to the caller's client),
`discountMode`, `discountValue`, `maxDiscountAmount`, `startDate`, `endDate`, `enabled`, `storeScope`, `storeIds`, `productIds`.
Preview body: `{ "offer": { ...same fields... }, "cartTotal": 100 }` or `{ "offer": {...}, "lines": [ { "productId": 1, "quantity": 2 } ] }`.
List reply: `{ "items": [...], "totalCount", "page", "size" }` (page starts at 0, size up to 500); `status` filter is
`ENABLED`, `DISABLED`, `SCHEDULED` or `EXPIRED`. Validation reply (400): `{ "message": "Validation failed", "errors": { "<field>": "<text>" } }`.

Validation errors are returned per field and are also enforced by the API, not only the screen.

## Kiosk API (additions only; old fields do not change)

- `POST /api/orders/quote`: every line also returns `originalUnitPrice`, `discountAmount`, `netAmount`, `taxAmount`
  and `offer` (id and name, or null). The reply also returns `discountAmount` (total) and `invoiceOffer` (or null).
  `subtotal`, `tax`, `total` keep their meaning (subtotal is net).
- `POST /api/orders`: runs the same pricing again; any discount sent by the client is ignored.
- Both `POST /api/orders/quote` and `POST /api/orders` answer HTTP 422 `{"error":"ORDER_TOTAL_NOT_POSITIVE","message":"Order total must be greater than zero"}` when the offers bring a non-empty cart to zero or less. No order is created.
- `GET /api/orders/{id}`: lines and totals include the discounts.
- `GET /api/stores/{id}/active-offers`: active offers of a store, for badges on product tiles.
- The amount sent to the card terminal is always the server total.
- If pricing cannot be reached the kiosk blocks checkout; it never falls back to local discount math.

## Odoo

Orders pushed to Odoo carry the discount on each line, so Odoo's session total equals what the customer paid.
Odoo does not need offers; nothing is created or pulled there. Each POS line is sent with the original unit price,
the discount as a percentage (Odoo's own field, rounded to 2 decimals, informational), and the exact line subtotal and
subtotal with tax that Waha charged, so the totals match to the cent whatever the percentage rounds to.
Orders queued before offers existed keep the old calculation. Status: written, **not yet verified against a live Odoo**
(the test database `hrco26` no longer exists). Verify on a test Odoo before the first discounted order reaches the client's Odoo.

## Feature level

Backend `api_level` becomes 2 with this feature (see `docs/api-levels.md`). Dashboard and kiosk hide offers
until the backend reports level 2 or higher.

## Caching

Active offers are cached per store for 60 seconds and the cache is cleared when an offer is created, changed, enabled,
disabled or deleted.

## Decisions taken (spec questions)

| # | Decision |
|---|---|
| Q1 | A product offer and a global offer stack; largest wins within each type |
| Q2 | A product amount discount is per unit |
| Q3 | An expired offer can be edited by changing its dates |
| Q4 | Products only, no categories |
| Q5 | Client managers manage offers; staff roles view only |
| Q6 | End date includes the whole day |
| Q7 | Kiosk shows badges on product tiles |
| - | The "For Limited Time" toggle is dropped; dates stay required |
| - | No offline orders or refunds exist yet; discounts are stored per line and per order so a future refund can use them |
