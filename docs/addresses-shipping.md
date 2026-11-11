# Addresses and shipping (online orders)

Status: built, not deployed.

Online (normal mode) only. The server goes by the mode of the session: only a signed-in user account whose session is in `NORMAL` mode gets shipping. The client app must send `sessionProperties.mode` at every login (`NORMAL` is the default, keep the user's last choice in preferences); a login without a mode gets no shipping. In shopping mode (also for the auto-created guest account, or a regular account used in shopping mode), kiosks, employees, and sessions with no mode, the quote has `shippingOptions: null` and a shipping method sent with an order is ignored. Those quotes and orders are exactly as before.

## Customer addresses

For any signed-in user (a device or an employee session gets 403). Everyone sees only their own addresses.
Base path `/api/addresses`, header `Authorization: Bearer <token>`.

| Call | What it does |
|---|---|
| `GET /api/addresses` | List, default first, then newest |
| `POST /api/addresses` | Add. 201 with the saved address |
| `PUT /api/addresses/{id}` | Change. Send the whole address |
| `PUT /api/addresses/{id}/default` | Make it the default |
| `DELETE /api/addresses/{id}` | Remove. 204. If it was the default, the newest other address becomes the default |

Fields: `label`, `recipientName`, `phone`, `buildingNumber`, `streetName`, `secondaryNumber`, `district`, `city`, `postalCode`, `countryCode`, `additionalInfo`, `isDefault`.
- Required: `city`, `streetName`, `phone` (digits, +, spaces, brackets, dash; 6 to 30 characters).
- `countryCode` is 2 letters, SA when left out. `buildingNumber`, `secondaryNumber`, `postalCode` take up to 10 characters, the rest up to 255 (`label` 60).
- The first address is the default automatically. `isDefault: true` on add or change makes that one the default.
- At most 20 addresses per user (409 after that). Unknown id or someone else's address: 404.
- A removed address is only hidden, so old orders keep their address.

## Shipping options

Methods today: `PICKUP` (receive at the branch) and `FREE_SHIPPING` (delivery to a saved address, no charge).

In normal mode, `POST /api/orders/quote` takes one new optional field, `addressId`, and returns one new field, `shippingOptions`:

```json
"shippingOptions": [
  {"method": "PICKUP",        "amount": 0.00, "requiresAddress": false, "available": true,  "reason": null},
  {"method": "FREE_SHIPPING", "amount": 0.00, "requiresAddress": true,  "available": false, "reason": "ADDRESS_REQUIRED"}
]
```

`available` for `FREE_SHIPPING` turns true once a valid `addressId` is sent. Show the options, let the customer pick the address, quote again with `addressId`. A bad `addressId` (not yours, removed) gives 400. Later rules (city, cart value, branch switch) only change `available` and `reason`, so build the screen from these two fields, not from the method names.

`amount` is added to the total once paid shipping exists. Today the total of the quote does not change.

## Placing the order

`POST /api/orders` takes two new optional fields:
- `shippingMethod`: `PICKUP` or `FREE_SHIPPING`
- `addressId`: required for `FREE_SHIPPING`, ignored for `PICKUP`

Errors: 400 for an unknown method, a missing or not-yours address, or `FREE_SHIPPING` without signing in; 422 `SHIPPING_NOT_AVAILABLE` when a rule blocks free delivery (not used yet; handle it).

The order response and `GET /api/orders/{id}` have three new fields: `shippingMethod` (null for kiosk and shopping mode orders), `shippingAmount`, and `shippingAddress`, a copy of the address at order time (null unless delivered). Editing or deleting the address afterwards does not change an existing order.

Retrying an order with the same `orderId` returns the existing order, shipping included.
