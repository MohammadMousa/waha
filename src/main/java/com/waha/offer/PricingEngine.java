package com.waha.offer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// Applies offers to a cart. Pure calculation: no database, no clock. See docs/offers.md for the rules.
public final class PricingEngine {

    private PricingEngine() {}

    public record Line(long productId, int quantity, BigDecimal unitPrice) {}

    public record AppliedOffer(long id, String name) {}

    public record PricedLine(long productId, int quantity, BigDecimal unitPrice, BigDecimal lineTotal,
                             BigDecimal ownDiscount, Offer ownOffer,
                             BigDecimal globalShare,
                             BigDecimal discountAmount, BigDecimal netAmount, BigDecimal taxAmount) {}

    public record Result(List<PricedLine> lines, BigDecimal subtotal, BigDecimal tax, BigDecimal total,
                         BigDecimal discountAmount, Offer globalOffer, BigDecimal globalDiscount) {}

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO = new BigDecimal("0.00");

    // currentOffers: offers already valid for the store and the date.
    public static Result price(List<Line> lines, List<Offer> currentOffers, BigDecimal vatRate) {
        int n = lines.size();
        BigDecimal[] gross = new BigDecimal[n];
        BigDecimal[] own = new BigDecimal[n];
        Offer[] ownOffer = new Offer[n];

        // 1-3: product offers per line.
        for (int i = 0; i < n; i++) {
            Line l = lines.get(i);
            gross[i] = l.unitPrice().multiply(BigDecimal.valueOf(l.quantity()));
            own[i] = ZERO;
            for (Offer o : currentOffers) {
                if (o.isGlobal() || !o.productIds().contains(l.productId())) continue;
                BigDecimal d = lineDiscount(o, l, gross[i]);
                if (better(d, o, own[i], ownOffer[i])) { own[i] = d; ownOffer[i] = o; }
            }
        }
        BigDecimal[] net = new BigDecimal[n];
        BigDecimal subtotal = ZERO;
        for (int i = 0; i < n; i++) {
            net[i] = gross[i].subtract(own[i]);
            subtotal = subtotal.add(net[i]);
        }

        // 4: global offer on what remains.
        Offer globalOffer = null;
        BigDecimal globalDiscount = ZERO;
        if (subtotal.signum() > 0) {
            for (Offer o : currentOffers) {
                if (!o.isGlobal()) continue;
                BigDecimal d = globalDiscount(o, subtotal);
                if (better(d, o, globalDiscount, globalOffer)) { globalDiscount = d; globalOffer = o; }
            }
        }

        // 5: spread it over the lines in proportion to their net amount; leftover cent to the largest line.
        BigDecimal[] share = new BigDecimal[n];
        BigDecimal given = ZERO;
        int largest = 0;
        for (int i = 0; i < n; i++) {
            share[i] = globalDiscount.signum() == 0 ? ZERO
                : globalDiscount.multiply(net[i]).divide(subtotal, 2, RoundingMode.HALF_UP);
            given = given.add(share[i]);
            if (net[i].compareTo(net[largest]) > 0) largest = i;
        }
        if (globalDiscount.signum() > 0) share[largest] = share[largest].add(globalDiscount.subtract(given));

        // 6-7: tax per line on the final net amount, then totals.
        List<PricedLine> out = new ArrayList<>(n);
        BigDecimal finalSubtotal = ZERO, tax = ZERO, discountTotal = ZERO;
        for (int i = 0; i < n; i++) {
            Line l = lines.get(i);
            BigDecimal finalNet = net[i].subtract(share[i]);
            BigDecimal lineTax = finalNet.multiply(vatRate).setScale(2, RoundingMode.HALF_UP);
            BigDecimal discount = own[i].add(share[i]);
            out.add(new PricedLine(l.productId(), l.quantity(), l.unitPrice(), gross[i], own[i], ownOffer[i],
                share[i], discount, finalNet, lineTax));
            finalSubtotal = finalSubtotal.add(finalNet);
            tax = tax.add(lineTax);
            discountTotal = discountTotal.add(discount);
        }
        return new Result(out, finalSubtotal, tax, finalSubtotal.add(tax), discountTotal, globalOffer, globalDiscount);
    }

    private static BigDecimal lineDiscount(Offer o, Line l, BigDecimal lineTotal) {
        BigDecimal d = Offer.PERCENTAGE.equals(o.discountMode())
            ? lineTotal.multiply(o.discountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
            : o.discountValue().multiply(BigDecimal.valueOf(l.quantity()));
        return cap(d, o.maxDiscountAmount(), lineTotal);
    }

    private static BigDecimal globalDiscount(Offer o, BigDecimal subtotal) {
        BigDecimal d = Offer.PERCENTAGE.equals(o.discountMode())
            ? subtotal.multiply(o.discountValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
            : o.discountValue();
        return cap(d, o.maxDiscountAmount(), subtotal);
    }

    // Never above the optional cap, never above the amount it applies to, never below zero.
    private static BigDecimal cap(BigDecimal d, BigDecimal max, BigDecimal base) {
        if (max != null && d.compareTo(max) > 0) d = max;
        if (d.compareTo(base) > 0) d = base;
        return d.setScale(2, RoundingMode.HALF_UP).max(BigDecimal.ZERO);
    }

    // Largest discount in money wins; a tie goes to the newest offer (highest id).
    private static boolean better(BigDecimal d, Offer o, BigDecimal bestD, Offer best) {
        if (d.signum() <= 0) return false;
        if (best == null) return true;
        int c = d.compareTo(bestD);
        return c > 0 || (c == 0 && o.id() > best.id());
    }

    public static final Comparator<Offer> NEWEST_FIRST = Comparator.comparingLong(Offer::id).reversed();
}
