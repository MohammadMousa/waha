# Coupons (code-activated discounts)
A coupon is a discount the customer activates by typing a **code**. It is standalone: it carries its own discount
rule and limits, and does not link to an offer. The pricing engine is shared with offers, so a coupon and an automatic
offer calculate in exactly the same way. The server prices every cart; apps only send the code and show the result.

## Fields
Empty (null) limits mean unlimited. Date-times are Riyadh local time without a zone, like `2026-12-31T23:59`.
| Field | Rules |
|---|---|
| code | 2 to 50 characters: letters, digits, dot, dash, underscore. Unique per organization, case-insensitive |
| name | Optional label, up to 120 characters |
| type | `GLOBAL` (whole cart) or `PRODUCT` (selected products). Cannot change after creation |
| discountMode, discountValue | `AMOUNT` or `PERCENTAGE`; value greater than 0, a percentage up to 100, 2 decimals |
| maxDiscountPerOrder | Optional cap on the coupon's discount for one order |
| storeScope, storeIds | `ALL`, or `SOME` with at least one store of the organization |
| productIds | Required for `PRODUCT`; each product must belong to the organization |
| enabled | Default true. Omitted on edit keeps the current value |
| startsAt, expiresAt | Optional. Expiry must be after the start |
| maxInvoices | Total paid orders allowed |
| maxInvoicesPerUser | Paid orders allowed per username |
| maxAmount | Total discount that can be given (excluding VAT) |
| maxAmountPerUser | Total discount per username (excluding VAT) |
| minimumOrderAmount | Net cart amount (after automatic offers, before VAT) needed to use the coupon |
| allowStackingWithOffers | Default true. When false, the coupon is refused if an automatic offer already discounts the cart |
| employeeId | Optional employee of the organization. For attribution in reports only; it does not restrict who may use the code |
| deletedAt | Soft delete, only for coupons no order has used. The code can then be reused |

Derived status (not stored): `ACTIVE`, `DISABLED`, `SCHEDULED`, `EXPIRED`, `EXHAUSTED` (total invoices or amount reached).

## How a coupon is applied
1. Automatic product offers are priced per line, then the global offer, exactly as without a coupon.
2. The coupon then discounts what remains (never below zero), before VAT. A global coupon spreads over the lines in
   proportion; a product coupon discounts only its products.
3. The coupon's cap for the order is the smallest of `maxDiscountPerOrder` and what is left of `maxAmount` and
   `maxAmountPerUser`. When the amount left is smaller than the discount, the last use is **capped**, not rejected.
4. VAT is computed per line on the final net amount and rounded half-up; lines add up to the order total.
5. At most **one coupon per order**.

Usage is counted from the redemptions whose order is **PAID**. Orders that are created but not paid do not use up a
limit, so a limit can be slightly exceeded by orders created before it was reached (accepted trade-off).
A retry of an order that already exists is not checked against the coupon again.

Per-user limits use the order's username. On kiosks the username is the device account, so these limits are accurate
only where the username identifies the customer (app login or phone number). A quote without a username skips the
per-user checks; order creation always runs them.

## Customer-facing API
`couponCode` is optional on a quote and on order creation. A quote may also send `username` (otherwise the session's
is used). Responses carry `coupon: {code, name, discountAmount}`; its discount is part of the total discount.
Rejections are `422` with `{error, message}`:

| Error | Meaning |
|---|---|
| COUPON_NOT_FOUND | Unknown code (also for a code of another organization) |
| COUPON_DISABLED, COUPON_NOT_YET_VALID, COUPON_EXPIRED | Switch or dates |
| COUPON_WRONG_STORE | Not valid in this store |
| COUPON_USAGE_LIMIT_REACHED | Total invoices reached |
| COUPON_USER_LIMIT_REACHED | Invoices per user reached |
| COUPON_AMOUNT_EXHAUSTED, COUPON_USER_AMOUNT_EXHAUSTED | No discount amount left (total, per user) |
| COUPON_MINIMUM_ORDER_NOT_MET | Cart below the minimum |
| COUPON_NOT_COMBINABLE | Coupon does not stack and an automatic offer applies |
| COUPON_NOT_APPLICABLE | No item in the cart qualifies |

## Admin API (`/api/admin/coupons`)
Needs `VIEW_COUPONS` (operator, branch admin, organization owner) to read and `MANAGE_COUPONS` (organization owner)
to change; super-admin has both. A super-admin must send `X-Organization-Id` (otherwise `409 ORGANIZATION_REQUIRED`).
Field problems return `400 {message, errors{field}}`.

| Call | Purpose |
|---|---|
| GET `/` | List with `q` (code or name), `status`, `employeeId`, `page`, `size` |
| GET `/{id}` | Detail with store and product ids and a usage summary |
| POST `/` | Create (201) |
| PUT `/{id}` | Full replace |
| PATCH `/{id}/enabled` | Enable or disable |
| DELETE `/{id}` | Soft delete (204). A coupon that orders have used cannot be deleted (409 `COUPON_IN_USE`); disable it instead |
| POST `/generate` | Many codes with one configuration, all or nothing: either `codes` (separated by commas, spaces or new lines) or `count` (1 to 500) with an optional `prefix` for random codes. A duplicate, invalid or used code rejects the whole call |
| GET `/{id}/redemptions` | Orders that used the coupon, with their status |

Every change is written to the coupon audit table (create, update, enable, disable, delete).

## Coupon sales report
`GET /api/admin/coupons/report` and `/report/export?format=csv|xlsx`. Only PAID orders. Filters: `from`, `to` (dates,
Riyadh time, inclusive, on payment time), `storeId`, `couponId`, `employeeId`, `username`. `groupBy` is `orders`
(default), `employee` or `coupon`; each has its own `sort` keys and `dir`, plus `page` and `size`.
The response has `summary` cards (`orders`, `subTotal`, `vat`, `total`, `discount`) over the whole filter, `items`,
and `total`. Amounts are the stored order amounts, so sub total plus VAT equals total; `discount` is the coupon's
discount excluding VAT. In the employee view a row with no `employeeId` means coupons without an employee.

## Tables
`coupons`, `coupon_stores`, `coupon_products`, `coupon_redemptions` (one row per order and coupon, with code and name
snapshots and the discount given) and `coupon_audit`.
