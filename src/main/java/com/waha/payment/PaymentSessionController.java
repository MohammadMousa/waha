package com.waha.payment;

import com.waha.common.ErrorResponse;
import com.waha.common.InvalidRequestException;
import com.waha.order.OrderNotFoundException;
import com.waha.order.OrderNotPayableException;
import com.waha.order.OrderService;
import com.waha.payment.dto.CreatePaymentSessionRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Kicks off the redirect-based flow for Normal/Shopping's web checkout -
// genuinely separate from POST /api/orders/{id}/pay (Kiosk's synchronous
// simulated flow), not a replacement for it. Kiosk keeps using /pay
// unchanged; this is additive.
@RestController
@RequestMapping("/api/orders")
public class PaymentSessionController {

    private final OrderService orderService;

    public PaymentSessionController(OrderService orderService) {
        this.orderService = orderService;
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
}
