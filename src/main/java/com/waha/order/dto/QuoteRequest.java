package com.waha.order.dto;

import java.util.List;

// storeId is required and explicit - a barcode's scan resolves differently
// per store (CHILD > PARENT > GLOBAL), so quoting a cart has to know which
// store it's for, same as checkout does.
public record QuoteRequest(Long storeId, List<OrderItemRequest> items) {}
