package com.waha.auth.dto;

import java.util.Map;

// sessionProperties: optional {"mode": "NORMAL"|"KIOSK"|"SHOPPING"}
// Most natural place for Kiosk mode to be set — the operator logs in then
// calls configureStore with storeId + mode=KIOSK to provision the device.
public record SelectStoreRequest(Long storeId, Map<String, String> sessionProperties) {}
