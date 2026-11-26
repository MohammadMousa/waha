package com.waha.payment;

import com.waha.common.ForbiddenException;
import com.waha.order.OrderNotFoundException;
import com.waha.order.OrderRepository;
import com.waha.payment.method.PaymentMethodRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimulatedPaymentGuardTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentMethodRepository methods = mock(PaymentMethodRepository.class);

    private void orderInStore(long storeId) {
        when(orders.getPaymentInfo("o1")).thenReturn(
            new OrderRepository.OrderPaymentInfo("CREATED", 0, BigDecimal.TEN, "SAR", storeId));
    }

    @Test
    void refusedWhenTheServerHasItOffEvenIfTheStoreAllowsIt() {
        orderInStore(5);
        when(methods.isSimulatedEnabledForStore(5)).thenReturn(true);
        assertThrows(ForbiddenException.class, () -> new SimulatedPaymentGuard(false, orders, methods).check("o1"));
    }

    @Test
    void refusedWhenTheStoreHasItOff() {
        orderInStore(5);
        when(methods.isSimulatedEnabledForStore(5)).thenReturn(false);
        assertThrows(ForbiddenException.class, () -> new SimulatedPaymentGuard(true, orders, methods).check("o1"));
    }

    @Test
    void allowedWhenBothAreOn() {
        orderInStore(5);
        when(methods.isSimulatedEnabledForStore(5)).thenReturn(true);
        assertDoesNotThrow(() -> new SimulatedPaymentGuard(true, orders, methods).check("o1"));
    }

    @Test
    void unknownOrderStaysNotFound() {
        when(orders.getPaymentInfo("o1")).thenThrow(new OrderNotFoundException("Order o1 not found"));
        assertThrows(OrderNotFoundException.class, () -> new SimulatedPaymentGuard(true, orders, methods).check("o1"));
    }
}
