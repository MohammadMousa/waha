package com.waha.order;

import com.waha.order.dto.OrderResponse;
import com.waha.payment.PaymentSseRegistry;
import com.waha.payment.PaymentStatus;
import com.waha.payment.RedirectPaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

// A payment confirmed online (webhook or return to the invoice page) must publish the paid event exactly once,
// so stock is taken and the Odoo push is queued, and an unsigned webhook must not be believed on its own.
class OnlinePaymentConfirmationTest {

    OrderRepository repo = mock(OrderRepository.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    PaymentSseRegistry sse = mock(PaymentSseRegistry.class);
    RedirectPaymentProvider myfatoorah = mock(RedirectPaymentProvider.class);
    RedirectPaymentProvider stripe = mock(RedirectPaymentProvider.class);
    OrderService service;

    @BeforeEach
    void setUp() {
        when(stripe.webhookIsSigned()).thenReturn(true);
        when(myfatoorah.webhookIsSigned()).thenReturn(false);
        service = new OrderService(repo, null, null, null, Map.of("myfatoorah", myfatoorah, "stripe", stripe),
            sse, events, null, null, null, null, null);
        when(repo.getPaymentInfo("o1")).thenReturn(new OrderRepository.OrderPaymentInfo("CREATED", 0, BigDecimal.TEN, "SAR", 5L));
        OrderResponse paid = mock(OrderResponse.class);
        when(paid.storeId()).thenReturn(5L);
        when(paid.currency()).thenReturn("SAR");
        when(repo.getOrderDetail("o1", true)).thenReturn(paid);
    }

    private void pending(String provider) {
        when(repo.findOrderIdByProviderReference("ref")).thenReturn(Optional.of(new OrderRepository.OrderIdAndProvider("o1", provider)));
    }

    @Test
    void confirmedWebhookPublishesThePaidEventOnce() {
        pending("myfatoorah");
        when(myfatoorah.checkStatus("ref")).thenReturn(PaymentStatus.PAID);
        when(repo.markPaid(eq("o1"), anyInt(), anyString(), anyString())).thenReturn(true);
        service.confirmPaymentFromWebhook("ref", PaymentStatus.PAID);
        ArgumentCaptor<Object> ev = ArgumentCaptor.forClass(Object.class);
        verify(events, times(1)).publishEvent(ev.capture());
        assertEquals("o1", ((OrderPaidEvent) ev.getValue()).orderId());
    }

    @Test
    void webhookTheProviderDoesNotConfirmIsIgnored() {
        pending("myfatoorah");
        when(myfatoorah.checkStatus("ref")).thenReturn(PaymentStatus.FAILED);
        service.confirmPaymentFromWebhook("ref", PaymentStatus.PAID);
        verify(repo, never()).markPaid(anyString(), anyInt(), anyString(), anyString());
        verify(repo, never()).recordPayment(anyString(), anyString(), anyString(), anyString(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void webhookIsIgnoredWhenTheProviderCannotBeReached() {
        pending("myfatoorah");
        when(myfatoorah.checkStatus("ref")).thenThrow(new RuntimeException("down"));
        service.confirmPaymentFromWebhook("ref", PaymentStatus.PAID);
        verify(repo, never()).markPaid(anyString(), anyInt(), anyString(), anyString());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void signedWebhookIsTrustedWithoutAskingTheProvider() {
        pending("stripe");
        when(repo.markPaid(eq("o1"), anyInt(), anyString(), anyString())).thenReturn(true);
        service.confirmPaymentFromWebhook("ref", PaymentStatus.PAID);
        verify(stripe, never()).checkStatus(anyString());
        verify(events, times(1)).publishEvent(any(OrderPaidEvent.class));
    }

    @Test
    void lostRaceDoesNotPublishAgain() {
        pending("stripe");
        when(repo.markPaid(eq("o1"), anyInt(), anyString(), anyString())).thenReturn(false);
        service.confirmPaymentFromWebhook("ref", PaymentStatus.PAID);
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void returnToTheInvoicePagePublishesTheEventToo() {
        when(repo.findPendingPayment("o1")).thenReturn(Optional.of(new OrderRepository.PendingPayment("myfatoorah", "ref")));
        when(myfatoorah.checkStatus("ref")).thenReturn(PaymentStatus.PAID);
        when(repo.markPaid(eq("o1"), anyInt(), anyString(), anyString())).thenReturn(true);
        service.verifyRedirectCallback("o1");
        verify(events, times(1)).publishEvent(any(OrderPaidEvent.class));
    }
}
