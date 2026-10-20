package com.waha.offer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

// "Today" for offers. Stores have no timezone field yet, so one zone is used for all (Riyadh by default).
// The kiosk clock is never used.
@Component
public class OfferClock {

    private final ZoneId zone;

    public OfferClock(@Value("${waha.offers.time-zone:Asia/Riyadh}") String zone) {
        this.zone = ZoneId.of(zone);
    }

    public LocalDate today() {
        return LocalDate.now(zone);
    }
}
