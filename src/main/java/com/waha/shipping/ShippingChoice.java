package com.waha.shipping;

import java.math.BigDecimal;

// The method the customer picked for an order, checked: the shipping amount and a copy of the address (JSON) to keep on the order.
public record ShippingChoice(String method, BigDecimal amount, Long addressId, String addressSnapshot) {}
