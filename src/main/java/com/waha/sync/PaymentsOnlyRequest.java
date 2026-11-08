package com.waha.sync;

import java.math.BigDecimal;
import java.util.List;

// Payment-only push for an order the server already holds (docs/offline.md section 6b).
// totalAmount is optional: what the kiosk believes it charged, used only to flag a difference.
public record PaymentsOnlyRequest(BigDecimal totalAmount, List<OfflineOrderRequest.Payment> payments) {}
