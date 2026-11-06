package com.waha.sync;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.device.DeviceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

// POST /api/sync/orders: a kiosk sends an order it created and paid on its own (docs/offline.md section 6).
// Errors: {error, message, retryable}. 401/403 = session problem (keep the order, stop sending);
// 422 = the order itself is wrong, 409 = it clashes with what the server holds; both are permanent.
// Anything else (5xx, timeouts) means try again later.
@RestController
@RequestMapping("/api/sync")
public class OfflineOrderController {

    private final SessionService sessionService;
    private final DeviceRepository deviceRepository;
    private final OfflineOrderService service;

    public OfflineOrderController(SessionService sessionService, DeviceRepository deviceRepository, OfflineOrderService service) {
        this.sessionService = sessionService;
        this.deviceRepository = deviceRepository;
        this.service = service;
    }

    @PostMapping("/orders")
    public ResponseEntity<?> push(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                  @RequestBody OfflineOrderRequest body) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.deviceId() == null) throw new ForbiddenException("A device session is required");
        var device = deviceRepository.findSyncInfo(session.deviceId());
        if (device.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Device not found"));

        try {
            var result = service.push(body, device.get());
            deviceRepository.updateSyncStatus(session.deviceId(), null, Instant.now(), null, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", result.result());
            out.put("orderId", result.orderId());
            out.put("displayId", result.displayId());
            out.put("orderReference", result.orderReference());
            return ResponseEntity.ok(out);
        } catch (OfflineOrderException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("error", e.code());
            out.put("message", e.getMessage());
            out.put("retryable", e.retryable());
            return ResponseEntity.status(e.status()).body(out);
        }
    }
}
