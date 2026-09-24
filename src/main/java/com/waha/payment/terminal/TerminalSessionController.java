package com.waha.payment.terminal;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.common.InvalidRequestException;
import com.waha.order.OrderNotPayableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api")
public class TerminalSessionController {

    private final TerminalSessionService service;
    private final SessionService sessionService;

    public TerminalSessionController(TerminalSessionService service, SessionService sessionService) {
        this.service = service;
        this.sessionService = sessionService;
    }

    // Kiosk: customer picks "Terminal Payment" — creates PENDING attempt.
    @PostMapping("/orders/{orderId}/terminal-session")
    public ResponseEntity<?> create(@PathVariable String orderId,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requireSession(auth);
        try {
            return ResponseEntity.ok(service.create(orderId));
        } catch (OrderNotPayableException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Terminal app: polls by id to track state (PENDING → CONFIRMED / TIMEOUT / CANCELLED).
    @GetMapping("/terminal-sessions/{id}")
    public ResponseEntity<?> get(@PathVariable String id,
                                 @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requireSession(auth);
        try {
            return ResponseEntity.ok(service.getById(id));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Terminal app: polls with no params — gets oldest PENDING terminal session globally.
    @GetMapping("/terminal-sessions/pending")
    public ResponseEntity<?> pending(@RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requireSession(auth);
        Optional<TerminalAttemptView> session = service.findPending();
        return session.map(ResponseEntity::ok)
                      .orElse(ResponseEntity.noContent().build());
    }

    // Terminal app: NFC card tap received — confirm payment.
    // Body: { "authCode": "...", "notes": { "brand": "VISA", "last4": "1234", ... } }
    @PostMapping("/terminal-sessions/{id}/confirm")
    public ResponseEntity<?> confirm(@PathVariable String id,
                                     @RequestBody Map<String, Object> body,
                                     @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requireSession(auth);
        try {
            String authCode = (String) body.get("authCode");
            @SuppressWarnings("unchecked")
            Map<String, Object> notes = (Map<String, Object>) body.get("notes");
            service.confirm(id, authCode, notes);
            return ResponseEntity.ok(Map.of("status", "CONFIRMED"));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Terminal app or kiosk: cancel the pending session.
    @PostMapping("/terminal-sessions/{id}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String id,
                                    @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requireSession(auth);
        service.cancel(id);
        return ResponseEntity.ok(Map.of("status", "CANCELLED"));
    }
}
