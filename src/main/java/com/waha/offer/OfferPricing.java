package com.waha.offer;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

// The one place that prices a cart with offers: used by cart quotes, order creation and invoices.
@Service
public class OfferPricing {

    private final OfferRepository offers;
    private final OfferClock clock;

    public OfferPricing(OfferRepository offers, OfferClock clock) {
        this.offers = offers;
        this.clock = clock;
    }

    public PricingEngine.Result price(long storeId, List<PricingEngine.Line> lines, BigDecimal vatRate) {
        return PricingEngine.price(lines, currentOffers(storeId), vatRate);
    }

    // Automatic offers valid today for a store (pricing and kiosk badges). Offers that need a coupon are never in it.
    public List<Offer> currentOffers(long storeId) {
        var today = clock.today();
        return offers.loadForStore(storeId).stream().filter(o -> o.isAuto() && o.isCurrent(today)).toList();
    }
}
