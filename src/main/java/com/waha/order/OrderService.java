package com.waha.order;

import com.waha.common.InvalidRequestException;
import com.waha.invoice.QrHelper;
import com.waha.order.dto.*;
import com.waha.payment.PaymentAttemptRequest;
import com.waha.payment.PaymentAttemptResult;
import com.waha.payment.PaymentProvider;
import com.waha.payment.PaymentSession;
import com.waha.payment.PaymentSseRegistry;
import com.waha.payment.PaymentStatus;
import com.waha.payment.RedirectPaymentProvider;
import com.waha.payment.dto.CreatePaymentSessionRequest;
import com.waha.payment.dto.PaymentSessionResponse;
import com.waha.product.Product;
import com.waha.product.ProductNotSellableException;
import com.waha.product.ProductRepository;
import com.waha.store.StoreConfig;
import com.waha.store.StoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final StoreRepository storeRepository;
    private final PaymentProvider paymentProvider;
    private final Map<String, RedirectPaymentProvider> redirectProviders;
    private final PaymentSseRegistry sseRegistry;
    private final ApplicationEventPublisher eventPublisher;

    private final com.waha.config.ConfigService configService;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository,
                         StoreRepository storeRepository, PaymentProvider paymentProvider,
                         Map<String, RedirectPaymentProvider> redirectProviders,
                         PaymentSseRegistry sseRegistry,
                         ApplicationEventPublisher eventPublisher,
                         com.waha.config.ConfigService configService) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.storeRepository = storeRepository;
        this.paymentProvider = paymentProvider;
        this.redirectProviders = redirectProviders;
        this.sseRegistry = sseRegistry;
        this.eventPublisher = eventPublisher;
        this.configService = configService;
    }

    private record Totals(BigDecimal subtotal, BigDecimal tax, BigDecimal total, List<OrderItemView> items, Map<Long, Product> productsById) {}

    private static long requireStoreId(Long storeId) {
        if (storeId == null) {
            throw new InvalidRequestException("storeId is required");
        }
        return storeId;
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidRequestException("username is required");
        }
        return username;
    }

    // Re-derives totals from the products table every time - never trusts a
    // client-supplied price - and re-checks `active` (a product could have
    // been deactivated between scan and checkout, e.g. discontinued or
    // recalled - a real human decision worth enforcing). Deliberately does
    // NOT second-guess a product's scope/price correctness beyond that: if
    // it resolved and priced at scan time, the sale proceeds - a system
    // uncertain about whether a productId "should" apply here is not a
    // reason to block a customer who's already holding the item. Price
    // discrepancies across branches are a data-entry/business-team concern,
    // surfaced later via reporting over the products table directly, not a
    // real-time gate here.
    private Totals computeTotals(List<OrderItemRequest> items, BigDecimal vatRate) {
        List<Long> productIds = items.stream().map(OrderItemRequest::productId).toList();
        Map<Long, Product> productsById = productRepository.findByIds(productIds).stream()
            .collect(Collectors.toMap(Product::id, p -> p));

        List<OrderItemView> itemViews = items.stream().map(i -> {
            Product p = productsById.get(i.productId());
            if (p == null) {
                throw new ProductNotSellableException("Product " + i.productId() + " does not exist");
            }
            if (!p.active()) {
                throw new ProductNotSellableException("Product " + p.name() + " is not currently sellable");
            }
            BigDecimal lineTotal = p.price().multiply(BigDecimal.valueOf(i.quantity()));
            return new OrderItemView(p.id(), p.barcode(), p.name(), i.quantity(), p.price(), lineTotal);
        }).toList();

        BigDecimal subtotal = itemViews.stream().map(OrderItemView::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = subtotal.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);

        return new Totals(subtotal, tax, total, itemViews, productsById);
    }

    // Called after every scan so the Flutter cart screen shows a live,
    // server-computed total - never a total it calculated on-device.
    public QuoteResponse quote(QuoteRequest request) {
        long storeId = requireStoreId(request.storeId());
        StoreConfig store = storeRepository.getStoreConfig(storeId);
        Totals totals = computeTotals(request.items(), store.vatRate());
        return new QuoteResponse(totals.subtotal(), totals.tax(), totals.total(), totals.items());
    }

    // request.orderId() is a UUID minted by the Flutter app the moment the
    // customer taps "Checkout" - online or offline. Calling this twice with
    // the same id is a safe no-op, which is exactly what an offline order
    // replaying on reconnect needs.
    public OrderResponse createOrder(CreateOrderRequest request) {
        long storeId = requireStoreId(request.storeId());
        String username = requireUsername(request.username());
        StoreConfig store = storeRepository.getStoreConfig(storeId);
        Totals totals = computeTotals(request.items(), store.vatRate());

        boolean wasCreated = orderRepository.createOrderIdempotent(
            request.orderId(), storeId, store.currency(), store.vatRate(), username, totals.subtotal(), totals.tax()
        );

        if (wasCreated) {
            orderRepository.insertOrderItems(request.orderId(), request.items(), totals.productsById());
            long displayId = orderRepository.nextDisplayId(storeId);
            orderRepository.setDisplayId(request.orderId(), displayId);
        }

        return orderRepository.getOrderDetail(request.orderId(), wasCreated);
    }

    public OrderResponse getOrder(String orderId) {
        return orderRepository.getOrderDetail(orderId, true);
    }

    public List<OrderRepository.OrderSummary> listUserOrders(String username, long storeId, int page, int size) {
        if (username == null || username.isBlank()) {
            throw new InvalidRequestException("username is required");
        }
        return orderRepository.findByUsername(username, storeId, page, Math.min(size, 100));
    }

    // Cancels a CREATED order owned by this username if no external payment
    // is in-flight (no PENDING attempt). Returns false if not cancellable
    // (wrong owner, already paid, or redirect already started).
    public boolean cancelOrder(String orderId, String username) {
        if (username == null || username.isBlank()) {
            throw new InvalidRequestException("Authentication required to cancel an order");
        }
        return orderRepository.cancelOrder(orderId, username);
    }

    // Every call is logged as a payment attempt regardless of outcome. A
    // decline is a normal result the Flutter app renders, not a server
    // error - only "can't be paid at all" (already paid / concurrent write)
    // throws.
    public PayOrderResponse payOrder(String orderId, PayOrderRequest request) {
        var info = orderRepository.getPaymentInfo(orderId);
        if ("PAID".equals(info.status())) {
            throw new OrderNotPayableException("Order " + orderId + " is already paid");
        }

        Map<String, String> metadata = request.simulateOutcome() != null
            ? Map.of("simulateOutcome", request.simulateOutcome())
            : Map.of();

        PaymentAttemptRequest attemptRequest = new PaymentAttemptRequest(
            orderId, info.totalAmount(), info.currency(), "order-" + orderId, metadata
        );
        PaymentAttemptResult result = paymentProvider.attempt(attemptRequest);

        orderRepository.recordPayment(orderId, result.provider(), result.status().name(), result.providerReference(), result.detail());

        if (result.status() != PaymentStatus.PAID) {
            return new PayOrderResponse(false, result.status().name(), result.detail(), orderRepository.getOrderDetail(orderId, true));
        }

        boolean advanced = orderRepository.markPaid(orderId, info.version(), result.providerReference(), result.provider());
        if (!advanced) {
            throw new OrderNotPayableException("Order " + orderId + " could not be marked paid (already paid, or modified concurrently)");
        }

        OrderResponse paid = orderRepository.getOrderDetail(orderId, true);
        eventPublisher.publishEvent(new OrderPaidEvent(orderId, paid.storeId(), paid.currency()));
        return new PayOrderResponse(true, "PAID", null, paid);
    }

    // Starts the redirect-based flow (Normal/Shopping web checkout) - a
    // genuinely different shape from payOrder above (Kiosk's synchronous
    // card-present simulation), not a variant of the same thing. Records a
    // PENDING attempt immediately with the provider's own reference
    // (Stripe Checkout Session id / MyFatoorah InvoiceId) - that's what
    // lets a later webhook find its way back to this order (see
    // OrderRepository.findOrderIdByProviderReference), and it's also the
    // audit trail if a customer starts a session and never completes it.
    public PaymentSessionResponse startCheckoutSession(String orderId, CreatePaymentSessionRequest request) {
        var info = orderRepository.getPaymentInfo(orderId);
        if ("PAID".equals(info.status())) {
            throw new OrderNotPayableException("Order " + orderId + " is already paid");
        }

        if (request.provider() == null || request.provider().isBlank()) {
            throw new InvalidRequestException("provider is required (e.g. \"stripe\" or \"myfatoorah\")");
        }
        // payment_methods.key may carry a _qr suffix for kiosk QR rows
        // (e.g. "stripe_qr") — strip it to reach the underlying provider bean.
        String providerKey = request.provider().toLowerCase().replace("_qr", "");
        RedirectPaymentProvider provider = redirectProviders.get(providerKey);
        if (provider == null) {
            throw new InvalidRequestException("Unknown payment provider: " + request.provider());
        }

        String defaultReturnUrl = configService.getPublicBaseUrl() + "/api/invoices/" + orderId;
        String successUrl = request.successUrl() != null ? request.successUrl() : defaultReturnUrl;
        String cancelUrl = request.cancelUrl() != null ? request.cancelUrl() : defaultReturnUrl;

        PaymentSession session = provider.createSession(info.totalAmount(), info.currency(), orderId, successUrl, cancelUrl);
        Instant expiresAt = Instant.now().plus(15, ChronoUnit.MINUTES);

        // Generate QR only for QR_LINK methods (kiosk); for plain REDIRECT the
        // Flutter app opens the URL in a browser and doesn't need a QR.
        boolean isQrFlow = "QR_LINK".equalsIgnoreCase(request.providerMode());
        String qrDataUri = isQrFlow ? QrHelper.generateDataUri(session.redirectUrl(), 300) : null;

        orderRepository.recordPendingAttempt(orderId, providerKey,
            session.providerReference(), session.redirectUrl(), qrDataUri, expiresAt);

        return new PaymentSessionResponse(session.redirectUrl(), qrDataUri, isQrFlow ? expiresAt : null);
    }

    // Called when the payment gateway redirects back to our invoice URL.
    // The redirect URL params (MyFatoorah ?paymentId=, Stripe ?session_id=) are
    // NOT reliable for lookup — MyFatoorah's paymentId differs from the InvoiceId
    // we stored (confirmed in commerce-platform). Instead, look up the PENDING
    // payment record we wrote at session creation and use ITS stored reference to
    // call checkStatus — the same reference the provider will recognise.
    public void verifyRedirectCallback(String orderId) {
        var info = orderRepository.getPaymentInfo(orderId);
        if ("PAID".equals(info.status()) || "CANCELLED".equals(info.status())) return;

        Optional<OrderRepository.PendingPayment> pending = orderRepository.findPendingPayment(orderId);
        if (pending.isEmpty()) return;

        String providerName = pending.get().provider();
        String storedRef = pending.get().providerReference();
        RedirectPaymentProvider provider = redirectProviders.get(providerName);
        if (provider == null) return;

        try {
            PaymentStatus status = provider.checkStatus(storedRef);
            log.info("Redirect callback verified — order={} provider={} status={}", orderId, providerName, status);
            if (status == PaymentStatus.PAID) {
                orderRepository.recordPayment(orderId, providerName, "PAID", storedRef, null);
                orderRepository.removePaymentAttempt(storedRef);
                orderRepository.markPaid(orderId, info.version(), storedRef, providerName);
                sseRegistry.notifyPaid(orderId);
            }
        } catch (Exception e) {
            log.warn("Redirect callback verification failed — order={} provider={}: {}", orderId, providerName, e.getMessage());
        }
    }

    // Called by the Stripe/MyFatoorah webhook controllers, never directly
    // by a client. Looks up which order a provider's own reference belongs
    // to, then reuses the exact same markPaid/recordPayment
    // mechanics payOrder uses - one path for "how does an order become
    // PAID", regardless of whether the confirmation was synchronous
    // (Kiosk) or async (webhook). Deliberately silent, not throwing, on an
    // unknown reference or an already-paid order - both are normal
    // outcomes here (a webhook redelivery, a race with something else),
    // and a webhook endpoint should never surface an error that makes the
    // provider retry pointlessly.
    public void confirmPaymentFromWebhook(String providerReference, PaymentStatus status) {
        Optional<OrderRepository.OrderIdAndProvider> match = orderRepository.findOrderIdByProviderReference(providerReference);
        if (match.isEmpty()) {
            return;
        }

        String orderId = match.get().orderId();
        String provider = match.get().provider();

        var info = orderRepository.getPaymentInfo(orderId);
        if ("PAID".equals(info.status()) || "CANCELLED".equals(info.status())) {
            return;
        }

        orderRepository.recordPayment(orderId, provider, status.name(), providerReference, null);
        orderRepository.removePaymentAttempt(providerReference);

        if (status == PaymentStatus.PAID) {
            orderRepository.markPaid(orderId, info.version(), providerReference, provider);
            sseRegistry.notifyPaid(orderId);
        }
    }
}
