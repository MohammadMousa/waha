# Payment Methods

> **Status:** active — updated 2026-08-26 to reflect QR payments and Mobile Payment.

---

## Provider Types

| Provider enum value | Flow | Initiated from |
|---------------------|------|----------------|
| `SIMULATED`         | Synchronous — instant success/fail | All modes (dev/demo) |
| `REDIRECT`          | External hosted checkout page, browser redirect | Normal, Shopping |
| `QR_LINK`           | Same as REDIRECT, but checkout URL is shown as a QR on the kiosk screen | Kiosk |
| `PAYMENT_URL`       | Invoice URL shown as QR — customer scans and pays on their own phone | Kiosk only |
| `TERMINAL`          | Card-present POS reader (Phase 3) | Kiosk |

---

## Database Schema

### `payment_methods` (global catalog)

```
id               BIGINT PK
key              VARCHAR(50) UNIQUE   -- stable machine key, e.g. "stripe", "stripe_qr"
display_name     JSON                 -- {"ar": "...", "en": "..."}, bilingual
provider         ENUM (see above)
available_modes  SET('NORMAL','KIOSK','SHOPPING')
active           BOOLEAN              -- global kill switch
sort_order       INT
payment_url      VARCHAR(500) NULL    -- PAYMENT_URL provider only: web-app base URL
```

### `payment_methods_store` (per-store overrides)

```
id                BIGINT PK
store_id          BIGINT FK → stores
payment_method_id BIGINT FK → payment_methods
active            BOOLEAN     -- per-store enable/disable (absence = inherits global)
sort_order        INT NULL    -- per-store sort (NULL = inherit global)
```

### `payment_attempts` (hot in-flight table)

Holds redirect/QR sessions while they are pending. Cleared on confirmation or by weekly cleanup.

```
id                 CHAR(36) PK
order_id           CHAR(36) FK → orders
provider           VARCHAR(50)
provider_reference VARCHAR(255)    -- Stripe session ID / MyFatoorah InvoiceId
redirect_url       TEXT
qr_data_uri        MEDIUMTEXT NULL -- base64 PNG for QR_LINK flows
expires_at         DATETIME
created_at         DATETIME DEFAULT NOW()
```

### `payments` (permanent audit log)

Append-only. Records finalized outcomes only (PAID / FAILED). PENDING rows live in `payment_attempts`.

```
id                 CHAR(36) PK
order_id           CHAR(36) FK → orders
provider           VARCHAR(50)
outcome            ENUM('PAID','FAILED')
provider_reference VARCHAR(255)
detail             TEXT NULL
attempted_at       DATETIME DEFAULT NOW()
```

---

## Flow Diagrams

### REDIRECT / QR_LINK (Stripe, MyFatoorah)

```
Flutter taps payment method chip
    │
    ├─ QR_LINK:  POST /api/orders/{id}/payment-session  (providerMode=QR_LINK)
    │               ↳ backend calls provider.createSession()
    │               ↳ generates QR PNG from checkout URL
    │               ↳ records row in payment_attempts
    │               ↳ returns { redirectUrl, qrCodeDataUri, expiresAt }
    │               ↳ Flutter shows QrPaymentScreen dialog
    │               ↳ customer scans QR → pays on their phone
    │               ↳ SSE on GET /api/orders/{id}/payment-events fires on webhook
    │               ↳ dialog closes, invoice shows PAID
    │
    └─ REDIRECT: POST /api/orders/{id}/payment-session  (providerMode=REDIRECT)
                    ↳ same backend session creation
                    ↳ Flutter opens redirectUrl in external browser
                    ↳ polls GET /api/orders/{id} every 2s
                    ↳ order becomes PAID → invoice updates
```

### PAYMENT_URL (Mobile Payment — kiosk only)

```
Flutter taps "Mobile Payment" chip
    │
    ├─ No backend session created
    ├─ Flutter constructs URL: {paymentUrl}/invoice/{orderId}
    ├─ Flutter generates QR locally (qr_flutter, no network call)
    ├─ Shows _MobilePaymentScreen dialog
    │     • Customer scans QR on their phone
    │     • Opens invoice in Shopping-mode web app
    │     • Pays via whatever methods are available there
    └─ Kiosk polls GET /api/orders/{id} every 3s until PAID
```

### SIMULATED (dev/demo)

```
Flutter shows confirmation sheet
    → POST /api/orders/{id}/pay  { simulateOutcome: "SUCCESS" }
    → instant PAID response
    → invoice transitions
```

### Webhook confirmation (REDIRECT / QR_LINK)

```
Stripe / MyFatoorah webhook → POST /api/webhooks/stripe  (or /myfatoorah)
    → controller looks up order via payment_attempts.provider_reference
    → calls orderService.confirmPaymentFromWebhook()
        ↳ marks order PAID (sp_mark_order_paid)
        ↳ writes final row to payments table
        ↳ deletes from payment_attempts
        ↳ fires SSE event → QrPaymentScreen dialog auto-closes
```

---

## Seeded Payment Methods

| key              | provider     | modes             | Notes |
|------------------|--------------|-------------------|-------|
| `simulated`      | SIMULATED    | NORMAL,KIOSK,SHOPPING | Dev/demo |
| `stripe`         | REDIRECT     | NORMAL,SHOPPING   | Real Stripe Checkout |
| `stripe_qr`      | QR_LINK      | KIOSK             | Same Stripe backend, QR UI |
| `myfatoorah`     | REDIRECT     | NORMAL,SHOPPING   | MyFatoorah hosted page |
| `myfatoorah_qr`  | QR_LINK      | KIOSK             | Same MyFatoorah backend, QR UI |
| `mobile_payment` | PAYMENT_URL  | KIOSK             | Invoice URL QR handoff |

---

## Admin Operations

### Enable / disable a method for a store

```
GET  /api/payment-methods/admin?storeId={id}      → list all with per-store active state
PUT  /api/payment-methods/{id}/store-active?active=true&storeId={id}
```

Requires `MANAGE_STORES` permission. Upserts a row in `payment_methods_store`. Absence of a row = method inherits global `active` state.

### Configure Mobile Payment URL

Set `payment_url` on the `mobile_payment` row in `payment_methods` to the Shopping-mode web-app URL (e.g. `https://store.example.com`). The kiosk appends `/invoice/{orderId}` and encodes it as QR. Leave empty to disable the method even if globally active.

---

## Backend Classes

| Class | Role |
|-------|------|
| `PaymentProvider` | Interface for synchronous card-present attempts (SIMULATED, TERMINAL) |
| `RedirectPaymentProvider` | Interface for hosted-checkout flows (createSession + checkStatus) |
| `SimulatedPaymentProvider` | Always-succeed implementation |
| `StripeRedirectPaymentProvider` | Stripe Checkout integration |
| `MyFatoorahRedirectPaymentProvider` | MyFatoorah hosted payment integration |
| `PaymentSessionController` | POST /payment-session, GET /payment-events (SSE) |
| `PaymentSseRegistry` | In-memory SSE emitter registry, fires on webhook |
| `PaymentMethodController` | GET /payment-methods, admin enable/disable endpoints |
| `PaymentMethodRepository` | DB queries for methods + per-store overrides |
| `OrderRepository.recordPendingAttempt()` | Writes in-flight session to payment_attempts |
| `OrderRepository.removePaymentAttempt()` | Cleans up after confirmation |
| `QrHelper.generateDataUri()` | ZXing-based QR PNG → base64 data URI |

---

## Error Handling

| Error | Backend response | Flutter display |
|-------|-----------------|----------------|
| `amount_too_small` (Stripe) | 400 + human-readable message | Shown inline under payment methods |
| Provider not found | 400 | Generic "Could not start payment" |
| Order already paid | 409 | "Order Already Paid" state |
| QR expires before payment | Client-side timer | QrPaymentScreen switches to expired state |
| Mobile Payment URL not configured | Client-side check before showing dialog | Inline error message |

---

## Planned (Advanced Phases)

- **TERMINAL** provider — card-present POS reader via Stripe Terminal SDK (Phase 3, hardware TBD)
- **Partial payment tracking** — `paid_amount` column on orders, `sp_mark_order_paid` accepts partial; Due amount on invoice reflects remainder
- **Weekly `payment_attempts` cleanup** — scheduled job to delete expired rows older than 7 days
