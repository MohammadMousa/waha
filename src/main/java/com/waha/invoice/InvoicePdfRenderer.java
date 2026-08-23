package com.waha.invoice;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.payment.dto.PaymentRecord;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

final class InvoicePdfRenderer {

    private static final Color COLOR_DARK      = new Color(0x22, 0x22, 0x22);
    private static final Color COLOR_MUTED     = new Color(0x66, 0x66, 0x66);
    private static final Color COLOR_PAID      = new Color(0x0a, 0x6b, 0x2e);
    private static final Color COLOR_UNPAID    = new Color(0x8a, 0x63, 0x00);
    private static final Color COLOR_CANCELLED = new Color(0x77, 0x77, 0x77);
    private static final Color COLOR_LINE      = new Color(0xdd, 0xdd, 0xdd);
    private static final Color COLOR_HEADER_BG = new Color(0xf0, 0xf0, 0xf0);
    private static final Color COLOR_ACCENT    = new Color(0x1a, 0x73, 0xe8);

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InvoicePdfRenderer() {}

    // lang="ar" → embed NotoSansArabic, prefer Arabic fields (ar > en fallback)
    // lang="en" (default) → Helvetica, prefer English fields (en > ar fallback)
    static byte[] render(OrderResponse order, ReceiptInfo receiptInfo, String lang) {
        boolean arabic = "ar".equalsIgnoreCase(lang);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // Font setup — embed Arabic-capable font when ar is requested
            BaseFont baseFont;
            BaseFont baseFontBold;
            if (arabic) {
                baseFont     = loadArabicFont("NotoSansArabic-Regular.ttf");
                baseFontBold = loadArabicFont("NotoSansArabic-Bold.ttf");
            } else {
                baseFont     = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
                baseFontBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            }

            Font titleFont  = new Font(baseFontBold, 16, Font.NORMAL, COLOR_DARK);
            Font boldFont   = new Font(baseFontBold, 10, Font.NORMAL, COLOR_DARK);
            Font normalFont = new Font(baseFont,     9,  Font.NORMAL, COLOR_DARK);
            Font mutedFont  = new Font(baseFont,     8,  Font.NORMAL, COLOR_MUTED);
            Font smallFont  = new Font(baseFont,     7,  Font.NORMAL, COLOR_MUTED);
            Font accentFont = new Font(baseFontBold, 9,  Font.NORMAL, COLOR_ACCENT);

            // ── Header band ──────────────────────────────────────────────────────────
            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1.4f, 1f});

            // Left: store info
            PdfPCell storeCell = borderlessCell();
            if (receiptInfo != null) {
                String storeName = arabic
                    ? coalesce(receiptInfo.nameAr(), receiptInfo.nameEn())
                    : coalesce(receiptInfo.nameEn(), receiptInfo.nameAr());
                if (storeName != null) storeCell.addElement(new Paragraph(storeName, titleFont));
                if (receiptInfo.addressText() != null) storeCell.addElement(new Paragraph(receiptInfo.addressText(), mutedFont));
                if (receiptInfo.vatNumber() != null)   storeCell.addElement(new Paragraph("VAT Reg: " + receiptInfo.vatNumber(), mutedFont));
                if (receiptInfo.crNumber() != null)    storeCell.addElement(new Paragraph("CR: " + receiptInfo.crNumber(), mutedFont));
            }
            header.addCell(storeCell);

            // Right: invoice meta
            PdfPCell invoiceCell = borderlessCell();
            invoiceCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

            boolean paid      = "PAID".equals(order.status());
            boolean cancelled = "CANCELLED".equals(order.status());
            Color statusColor = paid ? COLOR_PAID : cancelled ? COLOR_CANCELLED : COLOR_UNPAID;
            Font statusFont = new Font(baseFontBold, 10, Font.NORMAL, statusColor);

            Paragraph invLabel = new Paragraph("TAX INVOICE", titleFont);
            invLabel.setAlignment(Element.ALIGN_RIGHT);
            invoiceCell.addElement(invLabel);

            Paragraph statusLine = new Paragraph("[ " + order.status() + " ]", statusFont);
            statusLine.setAlignment(Element.ALIGN_RIGHT);
            invoiceCell.addElement(statusLine);

            if (order.displayId() != null) {
                Paragraph orderNo = new Paragraph("Order #" + order.displayId(), boldFont);
                orderNo.setAlignment(Element.ALIGN_RIGHT);
                invoiceCell.addElement(orderNo);
            }
            Paragraph refLine = new Paragraph("Ref: " + order.orderId(), smallFont);
            refLine.setAlignment(Element.ALIGN_RIGHT);
            invoiceCell.addElement(refLine);

            if (order.createdAt() != null) {
                String ds = DATE_FMT.format(ZonedDateTime.ofInstant(order.createdAt(), ZoneId.of("UTC")));
                Paragraph dl = new Paragraph("Date: " + ds + " UTC", mutedFont);
                dl.setAlignment(Element.ALIGN_RIGHT);
                invoiceCell.addElement(dl);
            }
            if (order.currency() != null) {
                Paragraph cl = new Paragraph("Currency: " + order.currency().toUpperCase(), mutedFont);
                cl.setAlignment(Element.ALIGN_RIGHT);
                invoiceCell.addElement(cl);
            }
            header.addCell(invoiceCell);
            doc.add(header);

            addDivider(doc);
            doc.add(Chunk.NEWLINE);

            // ── Billed to ────────────────────────────────────────────────────────────
            doc.add(new Paragraph("Billed to", accentFont));
            doc.add(new Paragraph(order.username(), boldFont));
            doc.add(Chunk.NEWLINE);

            // ── Items table ──────────────────────────────────────────────────────────
            sectionHeader(doc, "Items", boldFont);
            PdfPTable table = new PdfPTable(new float[]{4.5f, 1f, 1.5f, 1.5f});
            table.setWidthPercentage(100);
            addHeaderCell(table, "Description", boldFont);
            addHeaderCell(table, "Qty",         boldFont);
            addHeaderCell(table, "Unit Price",  boldFont);
            addHeaderCell(table, "Total",       boldFont);

            for (OrderItemView item : order.items()) {
                String name = arabic
                    ? extractLang(item.name(), "ar", "en")
                    : extractLang(item.name(), "en", "ar");
                addCell(table, name,                                        normalFont, Element.ALIGN_LEFT);
                addCell(table, String.valueOf(item.quantity()),             normalFont, Element.ALIGN_CENTER);
                addCell(table, fmtAmt(item.unitPrice(), order.currency()),  normalFont, Element.ALIGN_RIGHT);
                addCell(table, fmtAmt(item.lineTotal(), order.currency()),  normalFont, Element.ALIGN_RIGHT);
            }
            doc.add(table);
            doc.add(Chunk.NEWLINE);

            // ── Summary ──────────────────────────────────────────────────────────────
            sectionHeader(doc, "Summary", boldFont);
            PdfPTable summary = new PdfPTable(new float[]{3f, 1.5f});
            summary.setWidthPercentage(60);
            summary.setHorizontalAlignment(Element.ALIGN_RIGHT);
            addSummaryRow(summary, "Subtotal",
                fmtAmt(order.subtotal(), order.currency()), normalFont);
            addSummaryRow(summary, "VAT (" + formatPercent(order.taxRate()) + "%)",
                fmtAmt(order.tax(), order.currency()), normalFont);
            Font totalFont = new Font(baseFontBold, 11, Font.NORMAL, COLOR_DARK);
            addSummaryRow(summary, "Total",
                fmtAmt(order.total(), order.currency()), totalFont);
            doc.add(summary);
            doc.add(Chunk.NEWLINE);

            // ── Payments ─────────────────────────────────────────────────────────────
            if (order.payments() != null && !order.payments().isEmpty()) {
                sectionHeader(doc, "Payments", boldFont);
                PdfPTable pmtTable = new PdfPTable(new float[]{1.2f, 1f, 2f, 1.5f});
                pmtTable.setWidthPercentage(100);
                addHeaderCell(pmtTable, "Provider",   boldFont);
                addHeaderCell(pmtTable, "Status",     boldFont);
                addHeaderCell(pmtTable, "Reference",  boldFont);
                addHeaderCell(pmtTable, "Date (UTC)", boldFont);

                for (PaymentRecord p : order.payments()) {
                    String dateStr = p.attemptedAt() != null
                        ? DATE_FMT.format(ZonedDateTime.ofInstant(p.attemptedAt(), ZoneId.of("UTC")))
                        : "—";
                    boolean pmtPaid = "PAID".equals(p.outcome());
                    Font outcomeFont = new Font(baseFontBold, 8, Font.NORMAL, pmtPaid ? COLOR_PAID : COLOR_MUTED);
                    addCell(pmtTable, p.provider() != null ? p.provider() : "—",                normalFont, Element.ALIGN_LEFT);
                    addCell(pmtTable, p.outcome() != null ? p.outcome() : "—",                  outcomeFont, Element.ALIGN_LEFT);
                    addCell(pmtTable, p.providerReference() != null ? p.providerReference() : "—", smallFont,  Element.ALIGN_LEFT);
                    addCell(pmtTable, dateStr,                                                   normalFont, Element.ALIGN_LEFT);
                }
                doc.add(pmtTable);
                doc.add(Chunk.NEWLINE);
            }

            // ── Footer ───────────────────────────────────────────────────────────────
            Paragraph footer = new Paragraph("Generated by Waha POS", smallFont);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate invoice PDF", e);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    private static BaseFont loadArabicFont(String filename) throws Exception {
        try (InputStream is = InvoicePdfRenderer.class.getClassLoader()
                .getResourceAsStream("fonts/" + filename)) {
            if (is == null) throw new IllegalStateException("Arabic font not found: fonts/" + filename);
            byte[] bytes = is.readAllBytes();
            return BaseFont.createFont(filename, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }

    private static String extractLang(com.fasterxml.jackson.databind.JsonNode node, String primary, String fallback) {
        if (node == null) return "";
        com.fasterxml.jackson.databind.JsonNode p = node.path(primary);
        if (!p.isMissingNode() && !p.isNull() && !p.asText().isBlank()) return p.asText();
        com.fasterxml.jackson.databind.JsonNode f = node.path(fallback);
        if (!f.isMissingNode() && !f.isNull()) return f.asText();
        return node.asText("");
    }

    private static String coalesce(String first, String second) {
        return (first != null && !first.isBlank()) ? first : second;
    }

    private static void sectionHeader(Document doc, String text, Font boldFont) throws Exception {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(text, boldFont));
        c.setBackgroundColor(COLOR_HEADER_BG);
        c.setBorderColor(COLOR_LINE);
        c.setPadding(5);
        t.addCell(c);
        doc.add(t);
    }

    private static void addDivider(Document doc) throws Exception {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorderWidthBottom(1f);
        c.setBorderColor(COLOR_LINE);
        c.setBorderWidthTop(0);
        c.setBorderWidthLeft(0);
        c.setBorderWidthRight(0);
        c.setPaddingBottom(4);
        t.addCell(c);
        doc.add(t);
    }

    private static PdfPCell borderlessCell() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(8);
        return c;
    }

    private static void addHeaderCell(PdfPTable table, String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(COLOR_LINE);
        cell.setPadding(5);
        cell.setBackgroundColor(COLOR_HEADER_BG);
        table.addCell(cell);
    }

    private static void addCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBorderColor(COLOR_LINE);
        cell.setPadding(4);
        cell.setHorizontalAlignment(align);
        table.addCell(cell);
    }

    private static void addSummaryRow(PdfPTable table, String label, String value, Font font) {
        PdfPCell lc = new PdfPCell(new Phrase(label, font));
        lc.setBorderColor(COLOR_LINE);
        lc.setPadding(4);
        table.addCell(lc);
        PdfPCell vc = new PdfPCell(new Phrase(value, font));
        vc.setBorderColor(COLOR_LINE);
        vc.setPadding(4);
        vc.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(vc);
    }

    private static String fmtAmt(BigDecimal amount, String currency) {
        String num = amount != null ? amount.toPlainString() : "0.00";
        return currency != null ? num + " " + currency.toUpperCase() : num;
    }

    private static String formatPercent(BigDecimal rate) {
        if (rate == null) return "0";
        return rate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }
}
