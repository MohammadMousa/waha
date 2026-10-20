package com.waha.offer;

import com.waha.auth.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Kiosk: offers that apply today in a store, for badges and struck-through prices before items are in the cart.
// The kiosk never calculates a discount from these: the quote is the only source of prices.
@RestController
public class OfferKioskController {

    private final OfferPricing pricing;
    private final OfferRepository repo;
    private final SessionService sessionService;

    public OfferKioskController(OfferPricing pricing, OfferRepository repo, SessionService sessionService) {
        this.pricing = pricing;
        this.repo = repo;
        this.sessionService = sessionService;
    }

    @GetMapping("/api/stores/{storeId}/active-offers")
    public ResponseEntity<?> activeOffers(@RequestHeader(value = "Authorization", required = false) String auth,
                                          @PathVariable long storeId) {
        sessionService.requireSession(auth);
        List<Offer> offers = pricing.currentOffers(storeId);
        List<Map<String, Object>> items = offers.stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.id());
            m.put("name", o.name());
            m.put("type", o.type());
            m.put("discountMode", o.discountMode());
            m.put("discountValue", o.discountValue());
            m.put("maxDiscountAmount", o.maxDiscountAmount());
            m.put("startDate", o.startDate().toString());
            m.put("endDate", o.endDate().toString());
            m.put("productIds", o.productIds().stream().sorted().toList());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("items", items));
    }
}
