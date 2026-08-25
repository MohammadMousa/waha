package com.waha.invoice;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.payment.dto.PaymentRecord;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class InvoiceHtmlRenderer {

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InvoiceHtmlRenderer() {}

    static String render(OrderResponse order, ReceiptInfo info, String displayRef, String lang) {
        boolean ar = "ar".equalsIgnoreCase(lang);

        BigDecimal paidAmt = paidAmount(order);
        BigDecimal dueAmt  = order.total() != null ? order.total().subtract(paidAmt) : BigDecimal.ZERO;
        String taxPct      = taxPercent(order.taxRate());
        String dateStr     = order.createdAt() != null
            ? DATE_FMT.format(ZonedDateTime.ofInstant(order.createdAt(), ZoneId.of("UTC"))) + " UTC"
            : "";

        String fontImport = ar
            ? "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">"
            + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>"
            + "<link href=\"https://fonts.googleapis.com/css2?family=Noto+Naskh+Arabic&display=swap\" rel=\"stylesheet\">"
            : "";
        String fontFamily = ar ? "'Noto Naskh Arabic',Arial,sans-serif" : "'Segoe UI',Arial,sans-serif";
        String dir = ar ? "rtl" : "ltr";

        // Dynamic invoice title (paid vs unpaid), current language
        boolean isPaid   = "PAID".equals(order.status());
        String titleJson = isPaid
            ? (info != null ? info.paidInvoiceTitle()   : null)
            : (info != null ? info.unpaidInvoiceTitle() : null);
        String invTitle  = extractJsonLang(titleJson, lang);
        if (invTitle == null) invTitle = ar ? "فاتورة ضريبية" : "Tax Invoice";

        // Single-language store name with fallback
        String storeName = localName(info, ar);

        // QR code as data URI
        String qrDataUri = order.invoiceUrl() != null
            ? QrHelper.generateDataUri(order.invoiceUrl(), 100) : "";
        String qrHtml = qrDataUri.isEmpty()
            ? "<div class=\"hdr-qr\"></div>"
            : "<div class=\"hdr-qr\"><img src=\"" + qrDataUri + "\" width=\"90\" height=\"90\" alt=\"QR\"></div>";

        // ── CSS ──────────────────────────────────────────────────────────────────
        String css =
            "body{font-family:" + fontFamily + ";font-size:13px;color:#222;"
            + "max-width:860px;margin:1.5rem auto;padding:0 1.2rem}"
            // Header: [company+title | meta | QR]; dir=rtl flips to [QR | meta | company]
            + ".inv-header{display:flex;align-items:flex-start;gap:1.2rem;margin-bottom:.6rem}"
            + ".hdr-company{flex:1.5}"
            + ".hdr-meta{flex:1.2}"
            + ".hdr-qr{flex:0 0 auto}"
            + ".inv-title{font-size:1.5em;font-weight:800;margin:0 0 .15rem;line-height:1.2}"
            + ".inv-uuid{font-size:.72em;color:#aaa;margin:.1rem 0 .7rem;word-break:break-all}"
            + ".company-name{font-weight:700;font-size:.97em;margin:.6rem 0 .1rem}"
            + ".company-detail{font-size:.82em;color:#555;margin:.1rem 0}"
            // Meta 2-col table (dir=rtl makes label col appear on RIGHT in AR)
            + ".meta-tbl{border-collapse:collapse;font-size:.84em;width:100%}"
            + ".meta-tbl td{padding:3px 10px 3px 0;vertical-align:top}"
            + ".ml{color:#888;white-space:nowrap}"
            + ".mv{color:#333;font-weight:500}"
            // Separators
            + "hr.div-main{border:none;border-top:2px solid #222;margin:.6rem 0 0}"
            + "hr.div-sub{border:none;border-top:1px solid #ccc;margin:.3rem 0}"
            // Section sub-titles: outside table, bold, space above
            + ".sec-title{font-size:1.1em;font-weight:700;margin:1.3rem 0 .45rem;color:#111}"
            + ".billed-name{font-size:.9em;color:#333;margin:.1rem 0 .5rem}"
            // Items / payments table
            + "table.data-tbl{width:100%;border-collapse:collapse}"
            + ".data-tbl th{background:#eaeaea;padding:7px 8px;text-align:start;"
            + "font-size:.82em;font-weight:700;border-top:1px solid #ccc;"
            + "border-bottom:2px solid #bbb;white-space:nowrap;color:#333}"
            + ".data-tbl th.num,.data-tbl td.num{text-align:end}"
            + ".data-tbl td{padding:6px 8px;font-size:.85em;border-bottom:1px solid #eee;color:#333}"
            // Summary: 75% right-anchored, alternating rows
            + ".sum-wrap{margin-top:.75rem}"
            + ".sum-tbl{width:75%;margin-inline-end:auto;border-collapse:collapse}"
            + ".sum-tbl tr:nth-child(odd){background:#f8f8f5}"
            + ".sum-tbl td{padding:5px 10px;font-size:.88em}"
            + ".sum-tbl td:first-child{text-align:start}"
            + ".sum-tbl td:last-child{text-align:end}"
            + ".sum-total td{font-weight:700;border-top:2px solid #999;font-size:.95em}"
            + ".sum-due td{font-weight:700;font-size:1em;border-top:2px solid #555;"
            + "border-bottom:2px solid #555;background:#f0f0e8!important}"
            // Footer
            + ".footer{display:flex;gap:1.5rem;flex-wrap:wrap;margin-top:1.5rem;"
            + "padding-top:.6rem;border-top:1px solid #ddd;font-size:.75em;color:#999}"
            + ".footer-end{margin-inline-start:auto}";

        // ── Company + title block ─────────────────────────────────────────────────
        // DOM position: first in flex → LEFT in LTR, RIGHT in RTL
        StringBuilder companyBlock = new StringBuilder("<div class=\"hdr-company\">");
        companyBlock.append("<div class=\"inv-title\">").append(esc(invTitle)).append("</div>");
        companyBlock.append("<div class=\"inv-uuid\">").append(esc(order.orderId())).append("</div>");
        if (storeName != null)
            companyBlock.append("<div class=\"company-name\">").append(esc(storeName)).append("</div>");
        if (info != null && info.addressText() != null)
            companyBlock.append("<div class=\"company-detail\">").append(esc(info.addressText())).append("</div>");
        if (info != null && info.crNumber() != null)
            companyBlock.append("<div class=\"company-detail\">")
                        .append(ar ? "السجل التجاري: " : "Commercial Registry: ")
                        .append(esc(info.crNumber())).append("</div>");
        companyBlock.append("</div>");

        // ── Meta block (center): Date, Short ID, VAT, Currency ───────────────────
        // DOM position: second in flex → CENTER always
        // With dir=rtl: label col (first <td>) appears on RIGHT, value col on LEFT
        StringBuilder metaBlock = new StringBuilder("<div class=\"hdr-meta\"><table class=\"meta-tbl\">");
        if (!dateStr.isEmpty())
            metaBlock.append("<tr><td class=\"ml\">").append(ar ? "التاريخ" : "Date")
                     .append("</td><td class=\"mv\">").append(esc(dateStr)).append("</td></tr>");
        if (order.displayId() != null)
            metaBlock.append("<tr><td class=\"ml\">").append(ar ? "الرقم المختصر" : "Short ID")
                     .append("</td><td class=\"mv\">#").append(order.displayId()).append("</td></tr>");
        if (info != null && info.vatNumber() != null)
            metaBlock.append("<tr><td class=\"ml\">").append(ar ? "الرقم الضريبي" : "VAT number")
                     .append("</td><td class=\"mv\">").append(esc(info.vatNumber())).append("</td></tr>");
        if (order.currency() != null)
            metaBlock.append("<tr><td class=\"ml\">").append(ar ? "عملة الفاتورة" : "Invoice currency")
                     .append("</td><td class=\"mv\">").append(esc(order.currency().toUpperCase())).append("</td></tr>");
        metaBlock.append("</table></div>");

        // ── Items table ───────────────────────────────────────────────────────────
        // Same DOM column order for ALL languages — dir=rtl flips visually for AR
        String bcLabel = ar ? "كود المنتج" : "Barcode";
        String itemHeader = "<table class=\"data-tbl\"><tr>"
            + "<th>" + (ar ? "المنتج" : "Description") + "</th>"
            + "<th>" + bcLabel + "</th>"
            + "<th class=\"num\">" + (ar ? "الكمية" : "Qty") + "</th>"
            + "<th class=\"num\">" + (ar ? "سعر الوحدة" : "Unit Price") + "</th>"
            + "<th class=\"num\">" + (ar ? "الضريبة%" : "Tax%") + "</th>"
            + "<th class=\"num\">" + (ar ? "الضريبة" : "Tax") + "</th>"
            + "<th class=\"num\">" + (ar ? "الإجمالي" : "Total") + "</th>"
            + "</tr>";
        StringBuilder itemRows = new StringBuilder();
        for (OrderItemView item : order.items()) {
            String name  = extractName(item.name(), ar);
            BigDecimal tax   = itemTax(item.lineTotal(), order.taxRate());
            BigDecimal total = item.lineTotal() != null ? item.lineTotal().add(tax) : BigDecimal.ZERO;
            String bc = item.barcode() != null ? item.barcode() : "";
            itemRows.append("<tr>")
                .append("<td>").append(esc(name)).append("</td>")
                .append("<td style=\"font-size:.78em;color:#666\">").append(esc(bc)).append("</td>")
                .append("<td class=\"num\">").append(item.quantity()).append("</td>")
                .append("<td class=\"num\">").append(fmtAmt(item.unitPrice(), order.currency())).append("</td>")
                .append("<td class=\"num\">").append(taxPct).append("%</td>")
                .append("<td class=\"num\">").append(fmtAmt(tax, order.currency())).append("</td>")
                .append("<td class=\"num\">").append(fmtAmt(total, order.currency())).append("</td>")
                .append("</tr>");
        }

        // ── Payments table ────────────────────────────────────────────────────────
        String paymentsSection = "";
        if (order.payments() != null && !order.payments().isEmpty()) {
            String pmtHeader = "<table class=\"data-tbl\"><tr>"
                + "<th>" + (ar ? "مزود الدفع" : "Provider") + "</th>"
                + "<th>" + (ar ? "الحالة" : "Status") + "</th>"
                + "<th>" + (ar ? "المرجع" : "Reference") + "</th>"
                + "<th class=\"num\">" + (ar ? "التاريخ (UTC)" : "Date (UTC)") + "</th>"
                + "</tr>";
            StringBuilder pmtRows = new StringBuilder();
            for (PaymentRecord p : order.payments()) {
                String ds      = p.attemptedAt() != null
                    ? DATE_FMT.format(ZonedDateTime.ofInstant(p.attemptedAt(), ZoneId.of("UTC"))) : "—";
                String provRef = p.providerReference() != null ? p.providerReference() : "—";
                String outcome = p.outcome()           != null ? p.outcome()           : "—";
                String prov    = p.provider()          != null ? p.provider()          : "—";
                String color   = "PAID".equals(p.outcome()) || "SUCCESS".equals(p.outcome())
                    ? "#0a6b2e" : "#888";
                pmtRows.append("<tr>")
                    .append("<td>").append(esc(prov)).append("</td>")
                    .append("<td style=\"color:").append(color).append(";font-weight:600\">")
                        .append(esc(outcome)).append("</td>")
                    .append("<td style=\"font-size:.78em;color:#aaa\">").append(esc(provRef)).append("</td>")
                    .append("<td class=\"num\">").append(esc(ds)).append("</td>")
                    .append("</tr>");
            }
            paymentsSection =
                "<p class=\"sec-title\">" + (ar ? "المدفوعات" : "Payments") + "</p>"
                + "<div style=\"overflow-x:auto\">" + pmtHeader + pmtRows + "</table></div>";
        }

        // ── Summary ───────────────────────────────────────────────────────────────
        String summarySection =
            "<div class=\"sum-wrap\"><table class=\"sum-tbl\">"
            + sumRow(ar ? "إجمالي الأسعار" : "Total costs",      fmtAmt(order.subtotal(), order.currency()), false, false)
            + sumRow(ar ? "ضريبة القيمة المضافة (" + taxPct + "%)" : "Total taxes (" + taxPct + "%)", fmtAmt(order.tax(), order.currency()), false, false)
            + sumRow(ar ? "الإجمالي" : "Grand total",             fmtAmt(order.total(), order.currency()), true,  false)
            + sumRow(ar ? "إجمالي المدفوعات" : "Total payments",  fmtAmt(paidAmt,        order.currency()), false, false)
            + sumRow(ar ? "المستحق" : "Due",                      fmtAmt(dueAmt,          order.currency()), false, true)
            + "</table></div>";

        // ── Footer ────────────────────────────────────────────────────────────────
        StringBuilder footerHtml = new StringBuilder("<div class=\"footer\">");
        if (storeName != null)
            footerHtml.append("<span>").append(esc(storeName)).append("</span>");
        if (info != null && info.vatNumber() != null)
            footerHtml.append("<span>").append(ar ? "ض.ق.م: " : "VAT: ")
                      .append(esc(info.vatNumber())).append("</span>");
        if (info != null && info.crNumber() != null)
            footerHtml.append("<span>").append(ar ? "س.ت: " : "CR: ")
                      .append(esc(info.crNumber())).append("</span>");
        footerHtml.append("<span class=\"footer-end\">1 / 1</span></div>");

        // ── Assemble ──────────────────────────────────────────────────────────────
        // DOM order: [company+title | meta | QR]
        //   LTR visual: [company+title] [meta] [QR]  ← EN
        //   RTL visual: [QR] [meta] [company+title]  ← AR (dir=rtl flips flex row)
        return "<!DOCTYPE html><html dir=\"" + dir + "\" lang=\"" + (ar ? "ar" : "en") + "\">"
            + "<head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + fontImport
            + "<title>" + esc(invTitle) + "</title>"
            + "<style>" + css + "</style></head><body>"
            + "<div class=\"inv-header\">" + companyBlock + metaBlock + qrHtml + "</div>"
            + "<hr class=\"div-main\">"
            // Bill to
            + "<p class=\"sec-title\">" + (ar ? "العميل" : "Bill to") + "</p>"
            + "<p class=\"billed-name\">" + esc(order.username()) + "</p>"
            + "<hr class=\"div-sub\">"
            // Details
            + "<p class=\"sec-title\">" + (ar ? "تفاصيل الفاتورة" : "Details") + "</p>"
            + "<div style=\"overflow-x:auto\">" + itemHeader + itemRows + "</table></div>"
            // Payments (if any)
            + paymentsSection
            // Summary
            + "<p class=\"sec-title\">" + (ar ? "ملخص الفاتورة" : "Summary") + "</p>"
            + summarySection
            + footerHtml
            + "</body></html>";
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static String sumRow(String label, String value, boolean topBorder, boolean dueBorder) {
        String cls = topBorder ? " class=\"sum-total\"" : dueBorder ? " class=\"sum-due\"" : "";
        return "<tr" + cls + "><td>" + label + "</td><td>" + value + "</td></tr>";
    }

    private static BigDecimal paidAmount(OrderResponse order) {
        return "PAID".equals(order.status()) && order.total() != null
            ? order.total() : BigDecimal.ZERO;
    }

    private static BigDecimal itemTax(BigDecimal lineTotal, BigDecimal taxRate) {
        if (lineTotal == null || taxRate == null) return BigDecimal.ZERO;
        return lineTotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    private static String localName(ReceiptInfo info, boolean ar) {
        if (info == null) return null;
        String primary  = ar ? info.nameAr() : info.nameEn();
        String fallback = ar ? info.nameEn() : info.nameAr();
        return primary != null ? primary : fallback;
    }

    /** Extract lang-appropriate value from {"ar":"...","en":"..."} JSON string. */
    static String extractJsonLang(String json, String lang) {
        if (json == null || json.isBlank()) return null;
        String val = jsonStrValue(json, lang);
        if (val != null) return val;
        return jsonStrValue(json, "ar".equals(lang) ? "en" : "ar");
    }

    private static String jsonStrValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf('"', start);
        return end > start ? json.substring(start, end) : null;
    }

    static String extractName(JsonNode node, boolean preferAr) {
        if (node == null) return "";
        String first  = preferAr ? "ar" : "en";
        String second = preferAr ? "en" : "ar";
        JsonNode a = node.path(first);
        if (!a.isMissingNode() && !a.isNull()) return a.asText();
        JsonNode b = node.path(second);
        if (!b.isMissingNode() && !b.isNull()) return b.asText();
        return node.asText("");
    }

    private static String taxPercent(BigDecimal taxRate) {
        if (taxRate == null) return "0";
        return taxRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }

    private static String fmtAmt(BigDecimal amount, String currency) {
        String num = amount != null ? amount.toPlainString() : "0.00";
        return currency != null ? num + " " + currency.toUpperCase() : num;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
