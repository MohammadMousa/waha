package com.waha.payment;

import com.waha.common.ForbiddenException;
import com.waha.order.OrderRepository;
import com.waha.payment.method.PaymentMethodRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// Simulated payment is a test tool that marks an order paid with no money. It is refused unless this server turns it on
// AND the simulated payment method is on for the store of the order. The store is read from the order, never from the caller.
// The app mode is not checked: released apps send no sign-in on this call, so the server does not know their mode.
@Component
public class SimulatedPaymentGuard {

    private final boolean enabledOnServer;
    private final OrderRepository orders;
    private final PaymentMethodRepository methods;

    public SimulatedPaymentGuard(@Value("${waha.payment.simulated-enabled:false}") boolean enabledOnServer,
                                 OrderRepository orders, PaymentMethodRepository methods) {
        this.enabledOnServer = enabledOnServer;
        this.orders = orders;
        this.methods = methods;
    }

    // OrderNotFoundException for an unknown order, ForbiddenException when simulated payment is not allowed.
    public void check(String orderId) {
        if (!enabledOnServer) throw new ForbiddenException("Simulated payment is not enabled");
        long storeId = orders.getPaymentInfo(orderId).storeId();
        if (!methods.isSimulatedEnabledForStore(storeId)) {
            throw new ForbiddenException("Simulated payment is not enabled for this store");
        }
    }
}
