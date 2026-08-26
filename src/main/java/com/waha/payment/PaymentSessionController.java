package com.waha.payment;

import com.waha.common.ErrorResponse;
import com.waha.common.InvalidRequestException;
import com.waha.order.OrderNotFoundException;
import com.waha.order.OrderNotPayableException;
import com.waha.order.OrderService;
import com.waha.payment.dto.CreatePaymentSessionRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

// Kicks off the redirect/QR-based flow for Normal/Shopping/Kiosk checkout -
// genuinely separate from POST /api/orders/{id}/pay (simulated synchronous flow),
// not a replacement for it.
@RestController
@RequestMapping("/api/orders")
public class PaymentSessionController {

    private final OrderService orderService;
    private final PaymentSseRegistry sseRegistry;

    public PaymentSessionController(OrderService orderService, PaymentSseRegistry sseRegistry) {
        this.orderService = orderService;
        this.sseRegistry = sseRegistry;
    }

    @PostMapping("/{id}/payment-session")
    public ResponseEntity<?> createSession(@PathVariable String id, @RequestBody CreatePaymentSessionRequest request) {
        try {
            return ResponseEntity.ok(orderService.startCheckoutSession(id, request));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(new ErrorResponse(e.getMessage()));
        } catch (OrderNotPayableException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Kiosk subscribes here after rendering the QR. Sends a single
    // "payment_confirmed" event when the webhook fires, then the emitter
    // completes. 15-min timeout matches the QR link TTL.
    @GetMapping(value = "/{id}/payment-events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter paymentEvents(@PathVariable String id) {
        return sseRegistry.subscribe(id);
    }
}
