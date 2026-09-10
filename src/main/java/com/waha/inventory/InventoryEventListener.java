package com.waha.inventory;

import com.waha.order.OrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private final InventoryRepository repo;

    public InventoryEventListener(InventoryRepository repo) {
        this.repo = repo;
    }

    @EventListener
    public void onOrderPaid(OrderPaidEvent event) {
        if (event.storeId() == null) return;
        try {
            repo.deductForOrder(event.orderId(), event.storeId());
        } catch (Exception e) {
            log.error("Failed to deduct inventory for order {}: {}", event.orderId(), e.getMessage(), e);
        }
    }
}
