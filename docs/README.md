# Waha documentation

## Client API

| Document | Covers |
|---|---|
| [api/conventions.md](api/conventions.md) | Server addresses, sign-in header, errors, API level |
| [api/authentication.md](api/authentication.md) | Account, guest, employee and kiosk sign-in |
| [api/catalog.md](api/catalog.md) | Stores, categories, products, barcodes |
| [api/orders.md](api/orders.md) | Quote, order, payment calls, invoices |

These cover the calls a selling app needs. They are not a list of every backend call; admin features are described in the documents below.

## Features

| Area | Documents |
|---|---|
| Accounts and access | [roles-permissions.md](roles-permissions.md), [account-sessions.md](account-sessions.md), [customer-profile.md](customer-profile.md), [last-seen-and-request-load.md](last-seen-and-request-load.md) |
| Selling and pricing | [vat-pricing.md](vat-pricing.md), [offers.md](offers.md), [coupons.md](coupons.md), [addresses-shipping.md](addresses-shipping.md) |
| Orders and payments | [order-statuses.md](order-statuses.md), [payment-methods.md](payment-methods.md), [unpaid-orders.md](unpaid-orders.md) |
| Kiosk offline mode | [offline.md](offline.md), [sync-combined-pull.md](sync-combined-pull.md) |
| Content | [resource-management.md](resource-management.md), [landing-pages.md](landing-pages.md) |
| Back office | [inventory.md](inventory.md), [inventory-wastes.md](inventory-wastes.md), [audit.md](audit.md), [system-configuration.md](system-configuration.md) |
| Integrations | [odoo-integration.md](odoo-integration.md) |

## Project and operations

- [api-levels.md](api-levels.md): which features each backend level has
- [deployment.md](deployment.md): server settings and deploy steps
- [phases.md](phases.md): completed phases and backlog

## Keeping these current

The code is the source of truth. When a call changes, update its document in the same commit.
