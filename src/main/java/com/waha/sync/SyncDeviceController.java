package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.device.DeviceRepository;
import com.waha.pricing.VatPricing;
import com.waha.store.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// What a device needs to work offline (device-config) and how it reports its sync state (status).
// Both need a device session; an invalid session is a clear 401.
@RestController
@RequestMapping("/api/sync")
public class SyncDeviceController {

    private final SessionService sessionService;
    private final DeviceRepository deviceRepository;
    private final StoreRepository storeRepository;
    private final VatPricing vatPricing;
    private final JdbcTemplate jdbc;
    private final OfflineOrderService orderService;

    public SyncDeviceController(SessionService sessionService, DeviceRepository deviceRepository,
                                StoreRepository storeRepository, VatPricing vatPricing, JdbcTemplate jdbc,
                                OfflineOrderService orderService) {
        this.sessionService = sessionService;
        this.deviceRepository = deviceRepository;
        this.storeRepository = storeRepository;
        this.vatPricing = vatPricing;
        this.jdbc = jdbc;
        this.orderService = orderService;
    }

    @GetMapping("/device-config")
    public ResponseEntity<?> deviceConfig(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.deviceId() == null) throw new ForbiddenException("A device session is required");
        var info = deviceRepository.findSyncInfo(session.deviceId());
        if (info.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Device not found"));
        var d = info.get();
        var store = storeRepository.getStoreConfig(d.storeId());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceId", d.id());
        body.put("deviceNo", d.deviceNo());
        body.put("storeId", d.storeId());
        body.put("organizationId", d.organizationId());
        body.put("currency", store.currency());
        body.put("vatRate", store.vatRate());
        body.put("pricesIncludeVat", vatPricing.showWithVat(d.organizationId()));
        body.put("shortCode", shortCode(d.storeId()));
        body.put("lastReferenceSerial", orderService.lastReferenceSerial(d.id()));
        return ResponseEntity.ok(body);
    }

    // The store's optional reference prefix (null when not set).
    private String shortCode(long storeId) {
        String code = jdbc.query("SELECT short_code FROM stores WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, storeId);
        return code == null || code.isBlank() ? null : code;
    }

    // Body fields are all optional; an absent field keeps the stored value.
    @PostMapping("/status")
    public ResponseEntity<?> status(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                    @RequestBody JsonNode body) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.deviceId() == null) throw new ForbiddenException("A device session is required");
        try {
            deviceRepository.updateSyncStatus(session.deviceId(),
                instant(body, "lastPullAt"), instant(body, "lastPushAt"),
                count(body, "pendingOrders"), count(body, "failedOrders"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.noContent().build();
    }

    private static Instant instant(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) return null;
        try {
            return Instant.parse(n.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be an ISO date-time like 2026-11-06T10:15:30Z");
        }
    }

    private static Integer count(JsonNode body, String field) {
        JsonNode n = body.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.canConvertToInt() || n.asInt() < 0) throw new IllegalArgumentException(field + " must be a number of 0 or more");
        return n.asInt();
    }
}
