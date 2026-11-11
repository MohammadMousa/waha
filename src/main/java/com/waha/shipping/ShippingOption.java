package com.waha.shipping;

import java.math.BigDecimal;

// One way the customer can receive the order. available=false says why in reason (ADDRESS_REQUIRED when an address must be picked first).
public record ShippingOption(String method, BigDecimal amount, boolean requiresAddress, boolean available, String reason) {}
