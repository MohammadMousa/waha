package com.waha.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.payment.dto.PaymentRecord;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class InvoiceHtmlRenderer {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InvoiceHtmlRenderer() {}

    static String render(OrderResponse order, ReceiptInfo receiptInfo, String displayRef) {
        String ref = displayRef == null ? "both" : displayRef.toLowerCase();

        String storeHeader = buildStoreHeader(receiptInfo);
        String orderHeader = buildOrderHeader(order, ref);
        String statusClass = "status-" + order.status().toLowerCase();

        // Items rows
        StringBuilder items = new StringBuilder();
        for (OrderItemView item : order.items()) {
            String name = extractName(item.name());
            items.append("<tr><td>").append(escape(name)).append("</td><td>")
                .append(item.quantity()).append("</td><td>")
                .append(fmtAmt(item.unitPrice(), order.currency())).append("</td><td>")
                .append(fmtAmt(item.lineTotal(), order.currency())).append("</td></tr>");
        }

        // Payments rows
        StringBuilder pmtSection = new StringBuilder();
        if (order.payments() != null && !order.payments().isEmpty()) {
            pmtSection.append("<h3>Payments</h3>")
                .append("<table><tr><th>Provider</th><th>Status</th><th>Reference</th><th>Date (UTC)</th></tr>");
            for (PaymentRecord p : order.payments()) {
                String dateStr = p.attemptedAt() != null
                    ? DATE_FMT.format(ZonedDateTime.ofInstant(p.attemptedAt(), ZoneId.of("UTC")))
                    : "—";
                String outcomeClass = "PAID".equals(p.outcome()) ? "outcome-paid" : "outcome-other";
                pmtSection.append("<tr>")
                    .append("<td>").append(escape(p.provider() != null ? p.provider() : "—")).append("</td>")
                    .append("<td><span class=\"").append(outcomeClass).append("\">")
                    .append(escape(p.outcome() != null ? p.outcome() : "—")).append("</span></td>")
                    .append("<td class=\"ref\">").append(escape(p.providerReference() != null ? p.providerReference() : "—")).append("</td>")
                    .append("<td>").append(escape(dateStr)).append("</td>")
                    .append("</tr>");
            }
            pmtSection.append("</table>");
        }

        // Date line
        String dateLine = "";
        if (order.createdAt() != null) {
            String dateStr = DATE_FMT.format(ZonedDateTime.ofInstant(order.createdAt(), ZoneId.of("UTC")));
            dateLine = "<p class=\"meta\">Date: " + escape(dateStr) + " UTC</p>";
        }

        // VAT/CR footer
        String vatLabel = receiptInfo != null && receiptInfo.vatNumber() != null
            ? "VAT Reg: " + escape(receiptInfo.vatNumber()) + " | " : "";
        String crLabel = receiptInfo != null && receiptInfo.crNumber() != null
            ? "CR: " + escape(receiptInfo.crNumber()) : "";

        // Paid-via line
        String paidVia = "";
        if ("PAID".equals(order.status()) && order.paymentMethod() != null) {
            paidVia = "<p class=\"meta paid-via\">Paid via " + escape(displayMethod(order.paymentMethod())) + "</p>";
        }

        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>Invoice</title>" +
            "<style>" +
            "body{font-family:sans-serif;max-width:560px;margin:2rem auto;padding:0 1rem;color:#222}" +
            ".store{text-align:center;margin-bottom:1.5rem}" +
            ".store h1{margin:0;font-size:1.3em}" +
            ".store p{margin:0.25rem 0;font-size:0.85em;color:#555}" +
            "table{width:100%;border-collapse:collapse;margin:0.75rem 0}" +
            "td,th{text-align:left;padding:6px 4px;border-bottom:1px solid #eee;font-size:0.88em}" +
            "h3{font-size:0.95em;color:#444;margin:1.2rem 0 0.25rem;padding-bottom:4px;border-bottom:2px solid #eee}" +
            ".status{display:inline-block;padding:2px 10px;border-radius:12px;font-size:0.85em}" +
            ".status-paid{background:#d1f4dd;color:#0a6b2e}" +
            ".status-pending{background:#fff3cd;color:#8a6300}" +
            ".status-created{background:#fff3cd;color:#8a6300}" +
            ".status-cancelled{background:#f0f0f0;color:#777}" +
            ".outcome-paid{color:#0a6b2e;font-weight:bold}" +
            ".outcome-other{color:#888}" +
            ".total-row td{font-weight:bold;font-size:1.05em;border-top:2px solid #ccc;border-bottom:none}" +
            ".meta{color:#666;font-size:0.85em;margin:0.25rem 0}" +
            ".paid-via{color:#0a6b2e;font-weight:600}" +
            ".ref{font-size:0.72em;color:#aaa;word-break:break-all}" +
            ".footer{text-align:center;margin-top:2rem;font-size:0.75em;color:#aaa;border-top:1px solid #eee;padding-top:1rem}" +
            "</style></head><body>" +
            storeHeader +
            "<h2>Invoice &nbsp;<span class=\"status " + statusClass + "\">" + escape(order.status()) + "</span></h2>" +
            orderHeader +
            dateLine +
            "<p class=\"meta\">Billed to: <strong>" + escape(order.username()) + "</strong>" +
            (order.currency() != null ? " &nbsp;·&nbsp; Currency: " + escape(order.currency().toUpperCase()) : "") +
            "</p>" +
            "<h3>Items</h3>" +
            "<table><tr><th>Item</th><th>Qty</th><th>Unit</th><th>Total</th></tr>" +
            items +
            "</table>" +
            "<h3>Summary</h3>" +
            "<table>" +
            "<tr><td>Subtotal</td><td>" + fmtAmt(order.subtotal(), order.currency()) + "</td></tr>" +
            "<tr><td>VAT (" + formatPercent(order.taxRate()) + "%)</td><td>" + fmtAmt(order.tax(), order.currency()) + "</td></tr>" +
            "<tr class=\"total-row\"><td>Total</td><td>" + fmtAmt(order.total(), order.currency()) + "</td></tr>" +
            "</table>" +
            paidVia +
            pmtSection +
            "<div class=\"footer\">" + vatLabel + crLabel + "</div>" +
            "</body></html>";
    }

    private static String buildStoreHeader(ReceiptInfo info) {
        if (info == null) return "";
        StringBuilder sb = new StringBuilder("<div class=\"store\">");
        if (info.nameEn() != null || info.nameAr() != null) {
            sb.append("<h1>");
            if (info.nameEn() != null) sb.append(escape(info.nameEn()));
            if (info.nameAr() != null && info.nameEn() != null) sb.append(" / ");
            if (info.nameAr() != null) sb.append(escape(info.nameAr()));
            sb.append("</h1>");
        }
        if (info.addressText() != null) {
            sb.append("<p>").append(escape(info.addressText())).append("</p>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static String buildOrderHeader(OrderResponse order, String ref) {
        if ("display".equals(ref) && order.displayId() != null) {
            return "<p class=\"meta\">Order <strong>#" + order.displayId() + "</strong></p>";
        }
        if ("uuid".equals(ref)) {
            return "<p class=\"meta ref\">Ref: " + escape(order.orderId()) + "</p>";
        }
        StringBuilder sb = new StringBuilder("<p class=\"meta\">");
        if (order.displayId() != null) {
            sb.append("Order <strong>#").append(order.displayId()).append("</strong>");
            sb.append("<br><span class=\"ref\">Ref: ").append(escape(order.orderId())).append("</span>");
        } else {
            sb.append("<span class=\"ref\">Ref: ").append(escape(order.orderId())).append("</span>");
        }
        sb.append("</p>");
        return sb.toString();
    }

    static String extractName(JsonNode node) {
        if (node == null) return "";
        JsonNode en = node.path("en");
        if (!en.isMissingNode() && !en.isNull()) return en.asText();
        JsonNode ar = node.path("ar");
        if (!ar.isMissingNode() && !ar.isNull()) return ar.asText();
        return node.asText("");
    }

    private static String displayMethod(String provider) {
        if (provider == null) return "";
        return switch (provider.toLowerCase()) {
            case "stripe" -> "Stripe";
            case "myfatoorah" -> "MyFatoorah";
            case "simulated" -> "Terminal";
            default -> provider;
        };
    }

    private static String fmtAmt(java.math.BigDecimal amount, String currency) {
        String num = amount != null ? amount.toPlainString() : "0.00";
        if (currency == null) return num;
        return num + " " + currency.toUpperCase();
    }

    private static String formatPercent(java.math.BigDecimal rate) {
        if (rate == null) return "0";
        return rate.multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
