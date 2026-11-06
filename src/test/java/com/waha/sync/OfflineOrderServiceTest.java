package com.waha.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfflineOrderServiceTest {

    private static OfflineOrderRequest.Item item(int line, long product, int qty, String price) {
        return new OfflineOrderRequest.Item(line, product, qty, new BigDecimal(price));
    }

    @Test
    void totalsRoundTaxPerLineHalfUpAndLinesAddUp() {
        // 10.03 x 3 = 30.09, tax 4.5135 -> 4.51 ; 2.50 x 1 tax 0.375 -> 0.38 (half up)
        var t = OfflineOrderService.totals(List.of(item(1, 1, 3, "10.03"), item(2, 2, 1, "2.50")), new BigDecimal("0.15"));
        assertEquals(new BigDecimal("32.59"), t.subtotal());
        assertEquals(new BigDecimal("4.89"), t.tax());
        assertEquals(new BigDecimal("37.48"), t.total());
        assertEquals(List.of(new BigDecimal("4.51"), new BigDecimal("0.38")), t.lineTax());
    }

    @Test
    void referenceSerialContinuesAndWrapsAfter999() {
        assertEquals(0, OfflineOrderService.nextSerial(999));
        assertEquals(1, OfflineOrderService.nextSerial(0));
        assertEquals(234, OfflineOrderService.nextSerial(233));
        assertEquals("WH-01-233", OfflineOrderService.formatReference("WH", 1, 233));
        assertEquals("7-12-000", OfflineOrderService.formatReference("7", 12, 0));
        assertEquals(233, OfflineOrderService.serialOf("OSS-02-233"));
        assertEquals(7, OfflineOrderService.serialOf("W7K-29P-X4-007"));
        assertNull(OfflineOrderService.serialOf("NO-SERIAL"));
    }

    @Test
    void existingOrderDecisions() {
        assertEquals(OfflineOrderService.Action.MARK_PAID, OfflineOrderService.decide("CREATED", "PAID"));
        assertEquals(OfflineOrderService.Action.MARK_PAID, OfflineOrderService.decide("PENDING", "PAID"));
        assertEquals(OfflineOrderService.Action.MARK_CANCELLED, OfflineOrderService.decide("CREATED", "CANCELLED"));
        assertEquals(OfflineOrderService.Action.CANCELLED_CONFLICT, OfflineOrderService.decide("CANCELLED", "PAID"));
        // An older copy arriving late never moves the order backwards.
        assertEquals(OfflineOrderService.Action.NOTHING, OfflineOrderService.decide("PAID", "CREATED"));
        assertEquals(OfflineOrderService.Action.NOTHING, OfflineOrderService.decide("PAID", "PAID"));
        assertEquals(OfflineOrderService.Action.NOTHING, OfflineOrderService.decide("PAID", "CANCELLED"));
        assertEquals(OfflineOrderService.Action.NOTHING, OfflineOrderService.decide("CANCELLED", "CREATED"));
        assertEquals(OfflineOrderService.Action.NOTHING, OfflineOrderService.decide("CREATED", "CREATED"));
    }

    @Test
    void refusalsArePermanent() {
        var e = OfflineOrderException.invalid("TOTALS_MISMATCH", "x");
        assertFalse(e.retryable());
        assertEquals(422, e.status());
        assertEquals(409, OfflineOrderException.conflict("PAYMENT_CONFLICT", "x").status());
    }
}
