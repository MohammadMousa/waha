# VAT and displayed prices

How an app knows whether the prices it shows include VAT.

## The setting

Organization property `prices_exclude_vat` (string `"true"` or `"false"`, default `false`).

| Value | Meaning |
|---|---|
| `false` or missing | Prices shown to customers include VAT (the default) |
| `true` | Prices are shown without VAT, as stored |

Stored prices, invoices and Odoo always use the values without VAT. Only what customer apps and the dashboard show changes.

## Where an app reads it

- **Online apps (client, dashboard):** `GET /api/config` returns `prices_exclude_vat` only when the organization has set it.
  A missing key means `false`, so prices include VAT. Read it as: `excludeVat = (value == "true")`.
- **Offline kiosk:** `GET /api/sync/device-config` returns `pricesIncludeVat` (true or false), with the default already applied.
  It is the opposite of `prices_exclude_vat`.

## What changes in quote and order responses

When prices include VAT (the default), these fields are returned with VAT:
`unitPrice`, `lineTotal`, `discountAmount` (line and order level), and the coupon `discountAmount`.

These never change: `netAmount`, `taxAmount`, `subtotal`, `tax`, `total`, `taxRate`.

For each line, `lineTotal - discountAmount = netAmount + taxAmount`, so lines add up to the order total.

## Notes

- VAT-inclusive prices are a settled decision; the offline kiosk follows the same rule for its local prices.
- The setting is changed through the normal organization properties calls (`PUT /api/admin/organization/properties`).
