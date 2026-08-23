package com.waha.order;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.common.InvalidRequestException;
import com.waha.order.dto.*;
import com.waha.product.ProductNotSellableException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final SessionService sessionService;

    public OrderController(OrderService orderService, SessionService sessionService) {
        this.orderService = orderService;
        this.sessionService = sessionService;
    }

    // storeId in the request body still works exactly as before (explicit
    // always wins) - but once logged in with a store selected via POST
    // /api/auth/store, it can be omitted entirely and gets resolved from
    // the session instead. See SessionService.resolveStoreId.
    @PostMapping("/quote")
    public ResponseEntity<?> quote(@RequestBody QuoteRequest request,
                                    @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            QuoteRequest resolved = new QuoteRequest(
                sessionService.resolveStoreId(request.storeId(), authHeader),
                request.items()
            );
            return ResponseEntity.ok(orderService.quote(resolved));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(new ErrorResponse(e.getMessage()));
        } catch (ProductNotSellableException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
    }

    // request.orderId() is a UUID the Flutter app generated client-side -
    // lets "Checkout" safely retry on a flaky connection without risking a
    // duplicate order. storeId and username both resolve from the session
    // when omitted, same as quote - a logged-in customer never has to pass
    // either once authenticated and a store is selected. A guest (no
    // session) still has to supply both explicitly, same as before.
    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            CreateOrderRequest resolved = new CreateOrderRequest(
                sessionService.resolveStoreId(request.storeId(), authHeader),
                request.orderId(),
                sessionService.resolveUsername(request.username(), authHeader),
                request.items()
            );
            return ResponseEntity.ok(orderService.createOrder(resolved));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(400).body(new ErrorResponse(e.getMessage()));
        } catch (ProductNotSellableException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
    }

    // id is the UUID minted at checkout time - unguessable, so no separate
    // access token is required to read this order.
    @GetMapping("/{id}")
    public ResponseEntity<?> getOrder(@PathVariable String id) {
        try {
            return ResponseEntity.ok(orderService.getOrder(id));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        }
    }

    // Order history for the logged-in user. Requires auth - anonymous sessions
    // have no persistent identity to list orders for.
    @GetMapping
    public ResponseEntity<?> listOrders(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        try {
            String username = sessionService.resolveUsername(null, authHeader);
            return ResponseEntity.ok(orderService.listUserOrders(username, page, size));
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(401).body(new ErrorResponse("Authentication required to list orders"));
        }
    }

    // User-initiated cancel. Only works on CREATED orders with no PENDING
    // payment attempt. Returns 204 on success, 409 if not cancellable
    // (already paid, redirect in-flight, or wrong owner).
    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancelOrder(@PathVariable String id,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            String username = sessionService.resolveUsername(null, authHeader);
            boolean cancelled = orderService.cancelOrder(id, username);
            if (!cancelled) {
                return ResponseEntity.status(409).body(new ErrorResponse(
                    "Order cannot be cancelled (already paid, payment in-flight, or not your order)"));
            }
            return ResponseEntity.noContent().build();
        } catch (InvalidRequestException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/{id}/pay")
    public ResponseEntity<?> pay(@PathVariable String id, @RequestBody PayOrderRequest request) {
        try {
            return ResponseEntity.ok(orderService.payOrder(id, request));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        } catch (OrderNotPayableException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
    }
}
