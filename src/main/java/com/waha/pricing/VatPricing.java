package com.waha.pricing;

import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.order.dto.QuoteResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

// Shows prices with VAT on the fly. Stored prices and order records stay without VAT.
// Organization property prices_exclude_vat (default false): when false, the price fields that customer apps show
// are returned with VAT; when true, they are returned as stored (without VAT).
@Service
public class VatPricing {

    public static final String EXCLUDE_KEY = "prices_exclude_vat";

    private final JdbcTemplate jdbc;

    public VatPricing(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // True when prices of this organization are shown with VAT (the default).
    public boolean showWithVat(long organizationId) {
        String value = jdbc.query(
            "SELECT value FROM system_properties WHERE `key` = ? AND organization_id IN (0, ?) "
                + "ORDER BY organization_id DESC LIMIT 1",
            rs -> rs.next() ? rs.getString(1) : null, EXCLUDE_KEY, organizationId);
        return value == null || !"true".equalsIgnoreCase(value.trim());
    }

    public BigDecimal organizationVatRate(long organizationId) {
        List<BigDecimal> rows = jdbc.query("SELECT vat_rate FROM organizations WHERE id = ?",
            (rs, i) -> rs.getBigDecimal(1), organizationId);
        return rows.isEmpty() || rows.get(0) == null ? BigDecimal.ZERO : rows.get(0);
    }

    public static BigDecimal withVat(BigDecimal amount, BigDecimal rate) {
        if (amount == null) return null;
        BigDecimal r = rate == null ? BigDecimal.ZERO : rate;
        return amount.multiply(BigDecimal.ONE.add(r)).setScale(2, RoundingMode.HALF_UP);
    }

    // Each line shows what the customer pays for it: lineTotal - discountAmount = net amount + tax of that line,
    // so the lines add up to the order total however many there are. netAmount and taxAmount are unchanged.
    static OrderItemView inclusive(OrderItemView l, BigDecimal rate) {
        BigDecimal finalWithVat = l.netAmount().add(l.taxAmount());
        BigDecimal lineTotal = withVat(l.lineTotal(), rate);
        BigDecimal discount = lineTotal.subtract(finalWithVat);
        if (discount.signum() < 0) { lineTotal = finalWithVat; discount = BigDecimal.ZERO.setScale(2); }
        return new OrderItemView(l.productId(), l.barcode(), l.name(), l.quantity(), withVat(l.unitPrice(), rate),
            lineTotal, discount, l.netAmount(), l.taxAmount(), l.offer());
    }

    private static com.waha.order.dto.CouponView couponWithVat(com.waha.order.dto.CouponView c, BigDecimal rate) {
        return c == null ? null : new com.waha.order.dto.CouponView(c.code(), c.name(), withVat(c.discountAmount(), rate));
    }

    private static BigDecimal discountOf(List<OrderItemView> items) {
        return items.stream().map(OrderItemView::discountAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public QuoteResponse forQuote(QuoteResponse q, long organizationId, BigDecimal rate) {
        if (!showWithVat(organizationId)) return q;
        List<OrderItemView> items = q.items().stream().map(l -> inclusive(l, rate)).toList();
        return new QuoteResponse(q.subtotal(), q.tax(), q.total(), items, discountOf(items), q.invoiceOffer(),
            couponWithVat(q.coupon(), rate));
    }

    // Only for what customer apps and the dashboard show. Invoices and Odoo use the stored values.
    public OrderResponse forOrder(OrderResponse o, long organizationId) {
        if (!showWithVat(organizationId)) return o;
        List<OrderItemView> items = o.items().stream().map(l -> inclusive(l, o.taxRate())).toList();
        return new OrderResponse(o.orderId(), o.displayId(), o.storeId(), o.wasCreated(), o.status(), o.username(),
            o.subtotal(), o.tax(), o.total(), o.taxRate(), o.currency(), o.paymentReference(), o.paymentMethod(),
            o.invoiceUrl(), o.createdAt(), items, o.payments(), discountOf(items), o.invoiceOffer(),
            couponWithVat(o.coupon(), o.taxRate()), o.offlinePushed(), o.priceDiffers(), o.orderReference());
    }
}
