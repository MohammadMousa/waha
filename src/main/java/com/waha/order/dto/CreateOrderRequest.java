package com.waha.order.dto;

import java.util.List;

// orderId is a UUID (v7) the Flutter app generates itself, the moment the
// customer taps "Checkout" - before knowing whether the network is even
// reachable. It IS the idempotency key (retrying with the same id is a
// no-op) and it IS what an offline-created order replays as once
// connectivity returns. Generate with a UUID library client-side, not on
// the server.
//
// storeId is required and explicit (not read from server config) - the
// same backend can serve multiple stores, so which store an order belongs
// to has to come from the request, not be assumed.
//
// username is required, mandatory for ZATCA. Deliberately a plain string,
// not a typed field - a kiosk-account identifier, a real name from a
// webapp login, or a guest phone number/auto-generated UUID all land here
// as-is; which one it is gets read back out of the value's own pattern,
// not encoded as a separate column.
public record CreateOrderRequest(Long storeId, String orderId, String username, List<OrderItemRequest> items) {}
