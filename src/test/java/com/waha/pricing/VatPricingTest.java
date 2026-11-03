package com.waha.pricing;

import com.waha.order.dto.OrderItemView;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VatPricingTest {

    private static final BigDecimal RATE = new BigDecimal("0.15");

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    // A line as the pricing engine returns it: lineTotal before the offer, net = lineTotal - discount, tax on net.
    private static OrderItemView line(String unit, int qty, String discount) {
        BigDecimal lineTotal = d(unit).multiply(BigDecimal.valueOf(qty));
        BigDecimal net = lineTotal.subtract(d(discount));
        BigDecimal tax = net.multiply(RATE).setScale(2, java.math.RoundingMode.HALF_UP);
        return new OrderItemView(1, "b", null, qty, d(unit), lineTotal, d(discount), net, tax, null);
    }

    private static BigDecimal finalWithVat(OrderItemView l) { return l.lineTotal().subtract(l.discountAmount()); }

    @Test
    void priceWithVatRoundsToTwoDecimals() {
        assertEquals(d("11.50"), VatPricing.withVat(d("10.00"), RATE));
        assertEquals(d("3.45"), VatPricing.withVat(d("3.00"), RATE));
        assertEquals(d("10.00"), VatPricing.withVat(d("10.00"), null));
    }

    @Test
    void singleLineWithHalfOffFinalPriceEqualsTotal() {
        OrderItemView raw = line("10.00", 1, "5.00");           // net 5.00, tax 0.75, total 5.75
        OrderItemView v = VatPricing.inclusive(raw, RATE);
        assertEquals(d("11.50"), v.lineTotal());
        assertEquals(d("5.75"), finalWithVat(v));
        assertEquals(raw.netAmount().add(raw.taxAmount()), finalWithVat(v));
    }

    @Test
    void manyLinesAddUpToTheOrderTotal() {
        List<OrderItemView> raw = List.of(line("3.33", 3, "0.00"), line("7.77", 2, "1.55"),
            line("0.99", 7, "0.00"), line("12.49", 1, "6.25"));
        BigDecimal subtotal = raw.stream().map(OrderItemView::netAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = raw.stream().map(OrderItemView::taxAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sum = raw.stream().map(l -> finalWithVat(VatPricing.inclusive(l, RATE)))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(subtotal.add(tax), sum);
    }

    @Test
    void lineWithoutOfferHasNoDiscount() {
        OrderItemView v = VatPricing.inclusive(line("10.00", 2, "0.00"), RATE);
        assertEquals(0, v.discountAmount().signum());
        assertEquals(d("23.00"), v.lineTotal());
    }
}
