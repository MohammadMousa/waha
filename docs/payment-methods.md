# Payment Methods

> **Status:** work in progress — this document will be completed when real payment providers are integrated.

---

## Current state

Payment is handled through a `PaymentProvider` interface with a swap-in implementation per environment.

| Provider                    | Status | Description |
|-----------------------------|--------|-------------|
| `SimulatedPaymentProvider`  | Active | Always succeeds (or fails on demand). Used for development and demo. |
| Stripe Terminal             | Planned | Card-present terminal for physical kiosk. Implements the same `PaymentProvider` interface. |
| MyFatoorah                  | Planned | Alternative payment gateway. |

## Notes

- The `PaymentProvider` interface is shaped as a **single synchronous attempt** — not a redirect-to-hosted-page flow. This matches the kiosk card-present model where the result (success/fail) is known immediately.
- `payment_attempts` table records each attempt with its outcome and reference.
- `orders.payment_reference` is set to the provider's reference string on success.

---

*Full details (provider configuration, webhook handling, retry behavior) to be documented here.*
