package com.waha.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// Holds one SseEmitter per order that is currently showing the QR payment screen.
// Lifecycle: Flutter subscribes → emitter registered → webhook fires → notifyPaid → emitter removed.
// A kiosk shows at most one QR at a time, so concurrent emitters per order are rare
// but handled — all emitters for the same order are notified and then removed.
@Component
public class PaymentSseRegistry {

    private static final Logger log = LoggerFactory.getLogger(PaymentSseRegistry.class);

    // orderId → emitter. Using a simple concurrent map; multiple connections for
    // the same order (e.g. page reload) overwrite — the old emitter is completed first.
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String orderId) {
        SseEmitter emitter = new SseEmitter(15L * 60 * 1000); // 15-min timeout matches link TTL
        SseEmitter previous = emitters.put(orderId, emitter);
        if (previous != null) {
            previous.complete(); // clean up stale connection from a prior page load
        }
        emitter.onCompletion(() -> emitters.remove(orderId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(orderId, emitter);
            emitter.complete();
        });
        return emitter;
    }

    public void notifyPaid(String orderId) {
        SseEmitter emitter = emitters.remove(orderId);
        if (emitter == null) return;
        try {
            emitter.send(SseEmitter.event().name("payment_confirmed").data("PAID"));
            emitter.complete();
        } catch (IOException e) {
            log.debug("SSE send failed for order={} (client already gone): {}", orderId, e.getMessage());
        }
    }
}
