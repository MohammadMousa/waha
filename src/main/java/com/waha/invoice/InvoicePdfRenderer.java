package com.waha.invoice;

import com.ibm.icu.text.ArabicShaping;
import com.ibm.icu.text.ArabicShapingException;
import com.ibm.icu.text.Bidi;
import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import com.waha.order.dto.OrderItemView;
import com.waha.order.dto.OrderResponse;
import com.waha.payment.dto.PaymentRecord;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class InvoicePdfRenderer {

    private static final Color C_DARK    = new Color(0x22, 0x22, 0x22);
    private static final Color C_MUTED   = new Color(0x66, 0x66, 0x66);
    private static final Color C_LABEL   = new Color(0x88, 0x88, 0x88);
    private static final Color C_PAID    = new Color(0x0a, 0x6b, 0x2e);
    private static final Color C_MUTED2  = new Color(0x88, 0x88, 0x88);
    private static final Color C_LINE    = new Color(0xcc, 0xcc, 0xcc);
    private static final Color C_LINE2   = new Color(0x99, 0x99, 0x99);
    private static final Color C_HDR_BG  = new Color(0xea, 0xea, 0xea);
    private static final Color C_ROW_ALT = new Color(0xf8, 0xf8, 0xf5);
    private static final Color C_DUE_BG  = new Color(0xf0, 0xf0, 0xe8);

    private static final DateTimeFormatter DATE_FMT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private InvoicePdfRenderer() {}

    static byte[] render(OrderResponse order, ReceiptInfo info, String lang) {
        boolean ar = "ar".equalsIgnoreCase(lang);

        BigDecimal paidAmt = paidAmount(order);
        BigDecimal dueAmt  = order.total() != null ? order.total().subtract(paidAmt) : BigDecimal.ZERO;
        String taxPctStr   = taxPercent(order.taxRate());

        // Dynamic invoice title
        boolean isPaid   = "PAID".equals(order.status());
        String titleJson = isPaid
            ? (info != null ? info.paidInvoiceTitle()   : null)
            : (info != null ? info.unpaidInvoiceTitle() : null);
        String invTitle  = InvoiceHtmlRenderer.extractJsonLang(titleJson, lang);
        if (invTitle == null) invTitle = ar ? "فاتورة ضريبية" : "Tax Invoice";

        // Single-language store name
        String storeName = localName(info, ar);

        // Date string
        String dateStr = order.createdAt() != null
            ? DATE_FMT.format(ZonedDateTime.ofInstant(order.createdAt(), ZoneId.of("UTC"))) + " UTC"
            : "";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4);
        try {
            // Latin fonts — Helvetica is a built-in PDF font; always usable for Latin/numeric content
            BaseFont bfLat     = BaseFont.createFont(BaseFont.HELVETICA,      BaseFont.CP1252, BaseFont.NOT_EMBEDDED);
            BaseFont bfLatBold = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.CP1252, BaseFont.NOT_EMBEDDED);

            // Footer parts as separate segments (avoids BiDi clobbering Latin identifiers)
            String[] footerParts = buildFooterParts(info, storeName, ar);

            PdfWriter writer = PdfWriter.getInstance(doc, out);

            BaseFont[] fontHolder = new BaseFont[1];
            PageNumberEvent pageEvent = new PageNumberEvent(fontHolder, bfLat, footerParts);
            writer.setPageEvent(pageEvent);
            doc.open();

            // ── Fonts ─────────────────────────────────────────────────────────────
            BaseFont bf, bfBold;
            if (ar) {
                bf     = loadFont("NotoSansArabic-Regular.ttf");
                bfBold = loadFont("NotoSansArabic-Bold.ttf");
            } else {
                bf     = bfLat;
                bfBold = bfLatBold;
            }
            fontHolder[0] = bf;

            Font fTitle    = new Font(bfBold,    15, Font.NORMAL, C_DARK);
            Font fBold     = new Font(bfBold,    10, Font.NORMAL, C_DARK);
            Font fNormal   = new Font(bf,         9, Font.NORMAL, C_DARK);
            Font fMuted    = new Font(bf,         8, Font.NORMAL, C_MUTED);
            Font fLabel    = new Font(bf,         8, Font.NORMAL, C_LABEL);
            Font fSmall    = new Font(bf,         7, Font.NORMAL, C_MUTED);
            Font fSecTitle = new Font(bfBold,    11, Font.NORMAL, C_DARK);
            Font fTotal    = new Font(bfBold,    10, Font.NORMAL, C_DARK);
            Font fDue      = new Font(bfBold,    11, Font.NORMAL, C_DARK);
            // Latin fallback fonts — used for non-Arabic content in AR mode
            Font fNormalLat = new Font(bfLat,    9,  Font.NORMAL, C_DARK);
            Font fSmallLat  = new Font(bfLat,    7,  Font.NORMAL, C_MUTED);
            Font fMutedLat  = new Font(bfLat,    8,  Font.NORMAL, C_MUTED);
            Font fBoldLat   = new Font(bfLatBold, 8, Font.NORMAL, C_DARK);

            int cAlign = ar ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT;

            // ── Header: 3 cols ────────────────────────────────────────────────────
            // EN physical order: [companyCell | metaCell | qrCell]
            // AR physical order: [qrCell | metaCell | companyCell]
            PdfPTable header = new PdfPTable(3);
            header.setWidthPercentage(100);
            header.setWidths(new float[]{1.5f, 1.4f, 1f});

            // Company + title cell — uses mixed Chunk for Arabic label + Latin identifiers
            PdfPCell companyCell = cellNoBorder(cAlign);
            companyCell.addElement(para(t(invTitle, ar), fTitle, cAlign));
            companyCell.addElement(para(order.orderId(), fSmallLat, cAlign));
            companyCell.addElement(new Phrase("\n", fSmall));
            if (storeName != null) {
                Font snFont  = hasArabic(storeName) ? fBold : fBoldLat;
                String snStr = hasArabic(storeName) ? t(storeName, true) : storeName;
                companyCell.addElement(para(snStr, snFont, cAlign));
            }
            if (info != null && info.addressText() != null) {
                String addr = info.addressText();
                Font addrF  = hasArabic(addr) ? fMuted : fMutedLat;
                String addrS = hasArabic(addr) ? t(addr, true) : addr;
                companyCell.addElement(para(addrS, addrF, cAlign));
            }
            if (info != null && info.crNumber() != null) {
                if (ar) {
                    // Two separate right-aligned paragraphs avoids mixed-direction Chunk ordering issues
                    companyCell.addElement(para(t("السجل التجاري:", true), fMuted, Element.ALIGN_RIGHT));
                    companyCell.addElement(para(info.crNumber(), fMutedLat, Element.ALIGN_RIGHT));
                } else {
                    companyCell.addElement(para("Commercial Registry: " + info.crNumber(), fMuted, Element.ALIGN_LEFT));
                }
            }

            // Meta cell: 2-col inner table
            // AR: [value LEFT | label RIGHT]; EN: [label LEFT | value LEFT]
            PdfPCell metaCell = cellNoBorder(Element.ALIGN_LEFT);
            PdfPTable metaTbl = new PdfPTable(2);
            metaTbl.setWidthPercentage(100);
            metaTbl.setWidths(ar ? new float[]{1.8f, 1.2f} : new float[]{1.2f, 1.8f});
            if (!dateStr.isEmpty())
                addMetaRow(metaTbl, ar ? "التاريخ" : "Date",         dateStr,                          ar, fLabel, fSmallLat);
            if (order.displayId() != null)
                addMetaRow(metaTbl, ar ? "الرقم المختصر" : "Short ID", "#" + order.displayId(),        ar, fLabel, fSmallLat);
            if (info != null && info.vatNumber() != null)
                addMetaRow(metaTbl, ar ? "الرقم الضريبي" : "VAT number", info.vatNumber(),             ar, fLabel, fSmallLat);
            if (order.currency() != null)
                addMetaRow(metaTbl, ar ? "عملة الفاتورة" : "Invoice currency", order.currency().toUpperCase(), ar, fLabel, fNormalLat);
            metaCell.addElement(metaTbl);

            // QR cell
            PdfPCell qrCell = cellNoBorder(ar ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT);
            byte[] qrPng = order.invoiceUrl() != null
                ? QrHelper.generatePng(order.invoiceUrl(), 80) : new byte[0];
            if (qrPng.length > 0) {
                Image qrImg = Image.getInstance(qrPng);
                qrImg.scaleAbsolute(72, 72);
                qrCell.addElement(qrImg);
            }

            // Physical order: AR=[qr|meta|company], EN=[company|meta|qr]
            if (ar) {
                header.addCell(qrCell);
                header.addCell(metaCell);
                header.addCell(companyCell);
            } else {
                header.addCell(companyCell);
                header.addCell(metaCell);
                header.addCell(qrCell);
            }
            doc.add(header);

            // Thick separator (K&G style)
            addThickRule(doc);

            // ── Bill to ───────────────────────────────────────────────────────────
            sectionTitle(doc, t(ar ? "العميل" : "Bill to", ar), fSecTitle, cAlign);
            String uname = order.username() != null ? order.username() : "";
            boolean unameAr = hasArabic(uname);
            Paragraph billed = new Paragraph(unameAr ? t(uname, true) : uname,
                                             unameAr ? fNormal : fNormalLat);
            billed.setAlignment(cAlign);
            doc.add(billed);
            doc.add(Chunk.NEWLINE);

            // ── Details (items table) ─────────────────────────────────────────────
            sectionTitle(doc, t(ar ? "تفاصيل الفاتورة" : "Details", ar), fSecTitle, cAlign);

            // Column layout (desc, barcode, qty, unitPrice, tax%, tax, total)
            // AR uses reversed widths to match reversed cell insertion order
            float[] enWidths = {3f, 1.2f, 0.7f, 1.2f, 1.1f, 1f, 1.2f};
            float[] arWidths = {1.2f, 1f, 1.1f, 1.2f, 0.7f, 1.2f, 3f};
            PdfPTable items = new PdfPTable(ar ? arWidths : enWidths);
            items.setWidthPercentage(100);

            String[] enHeaders = {"Description",  "Barcode",      "Qty",    "Unit Price",  "Tax%",       "Tax",       "Total"};
            String[] arHeaders = {"المنتج",        "كود المنتج",  "الكمية", "سعر الوحدة", "الضريبة%",  "الضريبة",   "الإجمالي"};
            // EN: text=left, barcode=left, qty=center, numbers=right
            int[] enAligns = {Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_CENTER,
                               Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_RIGHT};
            // AR: text=right (in rightmost col), barcode=right, qty=center, numbers=left (in leftmost cols)
            int[] arAligns = {Element.ALIGN_RIGHT, Element.ALIGN_RIGHT, Element.ALIGN_CENTER,
                               Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT};
            int[] aligns = ar ? arAligns : enAligns;

            for (int i = ar ? 6 : 0; ar ? i >= 0 : i < 7; i += ar ? -1 : 1) {
                String txt = ar ? t(arHeaders[i], true) : enHeaders[i];
                hdrCellBg(items, txt, fBold, aligns[i]);
            }

            for (OrderItemView item : order.items()) {
                String name  = InvoiceHtmlRenderer.extractName(item.name(), ar);
                BigDecimal itax   = itemTax(item.lineTotal(), order.taxRate());
                BigDecimal itotal = item.lineTotal() != null ? item.lineTotal().add(itax) : BigDecimal.ZERO;
                String bc = item.barcode() != null ? item.barcode() : "";

                // Use Latin font for non-Arabic product names and always for barcodes
                boolean nameIsAr = ar && hasArabic(name);
                String[] vals = {
                    /* 0 desc      */ nameIsAr ? t(name, true) : name,
                    /* 1 barcode   */ bc,
                    /* 2 qty       */ String.valueOf(item.quantity()),
                    /* 3 unitPrice */ fmtAmt(item.unitPrice(), order.currency()),
                    /* 4 tax%      */ taxPctStr + "%",
                    /* 5 tax       */ fmtAmt(itax,   order.currency()),
                    /* 6 total     */ fmtAmt(itotal, order.currency())
                };
                Font[] valFonts = {nameIsAr ? fNormal : fNormalLat, fSmallLat,
                                   fNormal, fNormalLat, fNormalLat, fNormalLat, fNormalLat};

                for (int i = ar ? 6 : 0; ar ? i >= 0 : i < 7; i += ar ? -1 : 1) {
                    dataCell(items, vals[i], valFonts[i], aligns[i], false);
                }
            }
            doc.add(items);
            doc.add(Chunk.NEWLINE);

            // ── Payments (before summary) ─────────────────────────────────────────
            if (order.payments() != null && !order.payments().isEmpty()) {
                sectionTitle(doc, t(ar ? "المدفوعات" : "Payments", ar), fSecTitle, cAlign);

                // 4 cols: provider, status, reference, date
                // AR uses reversed widths to match reversed cell insertion order
                String[] enPmtH = {"Provider",    "Status",  "Reference", "Date (UTC)"};
                String[] arPmtH = {"مزود الدفع",  "الحالة", "المرجع",    "التاريخ (UTC)"};
                int[] enPmtA    = {Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_RIGHT};
                int[] arPmtA    = {Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT, Element.ALIGN_LEFT};
                int[] pmtA      = ar ? arPmtA : enPmtA;

                float[] enPmtW = {1.2f, 1f, 2f, 1.5f};
                float[] arPmtW = {1.5f, 2f, 1f, 1.2f};
                PdfPTable pmt = new PdfPTable(ar ? arPmtW : enPmtW);
                pmt.setWidthPercentage(100);
                for (int i = ar ? 3 : 0; ar ? i >= 0 : i < 4; i += ar ? -1 : 1)
                    hdrCellBg(pmt, ar ? t(arPmtH[i], true) : enPmtH[i], fBold, pmtA[i]);

                for (PaymentRecord p : order.payments()) {
                    String ds  = p.attemptedAt() != null
                        ? DATE_FMT.format(ZonedDateTime.ofInstant(p.attemptedAt(), ZoneId.of("UTC")))
                        : "—";
                    boolean ok = "PAID".equals(p.outcome()) || "SUCCESS".equals(p.outcome());
                    Font outF  = new Font(bfLatBold, 8, Font.NORMAL, ok ? C_PAID : C_MUTED2);
                    String prov = p.provider()          != null ? p.provider()          : "—";
                    String out2 = p.outcome()           != null ? p.outcome()           : "—";
                    String ref  = p.providerReference() != null ? p.providerReference() : "—";

                    // Payment data is always Latin/numeric — use Latin fonts
                    String[] pv = {prov, out2, ref, ds};
                    Font[]   pf = {fNormalLat, outF, fSmallLat, fNormalLat};
                    for (int i = ar ? 3 : 0; ar ? i >= 0 : i < 4; i += ar ? -1 : 1)
                        dataCell(pmt, pv[i], pf[i], pmtA[i], false);
                }
                doc.add(pmt);
                doc.add(Chunk.NEWLINE);
            }

            // ── Summary ───────────────────────────────────────────────────────────
            sectionTitle(doc, t(ar ? "ملخص الفاتورة" : "Summary", ar), fSecTitle, cAlign);

            // Inner summary table: EN=[label(wide) | value(narrow)], AR=[value(narrow) | label(wide)]
            float[] sumInnerWidths = ar ? new float[]{1.5f, 2.5f} : new float[]{2.5f, 1.5f};
            PdfPTable sumInner = new PdfPTable(sumInnerWidths);
            sumInner.setWidthPercentage(100);
            int sumLa = ar ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT;
            int sumVa = ar ? Element.ALIGN_LEFT  : Element.ALIGN_RIGHT;

            sumRow(sumInner, t(ar ? "إجمالي الأسعار"                              : "Total costs",                ar), fmtAmt(order.subtotal(), order.currency()), fNormal, fNormalLat, sumLa, sumVa, false, false, C_ROW_ALT, ar);
            sumRow(sumInner, t(ar ? "ضريبة القيمة المضافة (" + taxPctStr + "%)"   : "Total taxes (" + taxPctStr + "%)", ar), fmtAmt(order.tax(),      order.currency()), fNormal, fNormalLat, sumLa, sumVa, false, false, null,      ar);
            sumRow(sumInner, t(ar ? "الإجمالي"                                     : "Grand total",                ar), fmtAmt(order.total(),    order.currency()), fTotal,  fNormalLat, sumLa, sumVa, true,  false, C_ROW_ALT, ar);
            sumRow(sumInner, t(ar ? "إجمالي المدفوعات"                             : "Total payments",             ar), fmtAmt(paidAmt,          order.currency()), fNormal, fNormalLat, sumLa, sumVa, false, false, null,      ar);
            sumRow(sumInner, t(ar ? "المستحق"                                      : "Due",                        ar), fmtAmt(dueAmt,           order.currency()), fDue,    fNormalLat, sumLa, sumVa, false, true,  C_DUE_BG,  ar);

            PdfPCell innerCell = new PdfPCell(sumInner);
            innerCell.setBorder(Rectangle.NO_BORDER);
            innerCell.setPadding(0);

            PdfPCell spacer = new PdfPCell(new Phrase(" "));
            spacer.setBorder(Rectangle.NO_BORDER);

            // EN: summary left (75%), spacer right (25%)
            // AR: spacer left (25%), summary right (75%)
            PdfPTable sumOuter = new PdfPTable(ar ? new float[]{25f, 75f} : new float[]{75f, 25f});
            sumOuter.setWidthPercentage(100);
            if (ar) {
                sumOuter.addCell(spacer);
                sumOuter.addCell(innerCell);
            } else {
                sumOuter.addCell(innerCell);
                sumOuter.addCell(spacer);
            }
            doc.add(sumOuter);

        } catch (Exception e) {
            throw new RuntimeException("Failed to generate invoice PDF", e);
        } finally {
            doc.close();
        }
        return out.toByteArray();
    }

    // ── Page numbering + footer event ─────────────────────────────────────────────

    private static final class PageNumberEvent extends PdfPageEventHelper {
        private final BaseFont[] fontHolder;
        private final BaseFont bfLat;
        private final String[] footerParts;
        private PdfTemplate totalPagesTemplate;

        PageNumberEvent(BaseFont[] fontHolder, BaseFont bfLat, String[] footerParts) {
            this.fontHolder   = fontHolder;
            this.bfLat        = bfLat;
            this.footerParts  = footerParts;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document doc) {
            totalPagesTemplate = writer.getDirectContent().createTemplate(20, 10);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            BaseFont bfAr = fontHolder[0];
            BaseFont bf   = bfAr != null ? bfAr : bfLat;
            PdfContentByte cb = writer.getDirectContent();
            float y  = doc.bottom() - 12;
            float sz = 7f;
            Color gray = new Color(0x99, 0x99, 0x99);

            // Footer: draw each segment separately with per-segment font selection.
            // This prevents BiDi from mangling Latin identifiers (e.g. "CR-0001") that
            // are embedded in Arabic text when the whole line is processed as one string.
            if (footerParts != null) {
                float x = doc.left();
                for (String part : footerParts) {
                    if (part == null || part.isEmpty()) continue;
                    BaseFont segFont = (bfAr != null && hasArabic(part)) ? bfAr : bfLat;
                    cb.beginText();
                    cb.setFontAndSize(segFont, sz);
                    cb.setColorFill(gray);
                    cb.setTextMatrix(x, y);
                    cb.showText(part);
                    cb.endText();
                    x += segFont.getWidthPoint(part, sz) + 4;
                }
            }

            // Page number: "X / Y" — calculate text width dynamically so numbers sit close together
            String pageText  = writer.getPageNumber() + " / ";
            float  ptw       = bf.getWidthPoint(pageText, sz);
            float  tmplW     = 20f;
            float  tmplX     = doc.right() - 10 - tmplW;
            float  ptX       = tmplX - ptw - 2;

            cb.beginText();
            cb.setFontAndSize(bfLat, sz);  // always Latin font for page numbers
            cb.setColorFill(gray);
            cb.setTextMatrix(ptX, y);
            cb.showText(pageText);
            cb.endText();
            cb.addTemplate(totalPagesTemplate, tmplX, y);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document doc) {
            totalPagesTemplate.beginText();
            totalPagesTemplate.setFontAndSize(bfLat, 7);
            totalPagesTemplate.setColorFill(new Color(0x99, 0x99, 0x99));
            totalPagesTemplate.setTextMatrix(0, 0);
            totalPagesTemplate.showText(String.valueOf(writer.getPageNumber() - 1));
            totalPagesTemplate.endText();
        }
    }

    // ── Layout helpers ────────────────────────────────────────────────────────────

    private static void sectionTitle(Document doc, String text, Font font, int align) throws Exception {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(align);
        p.setSpacingBefore(12);
        p.setSpacingAfter(4);
        doc.add(p);
    }

    private static void addThickRule(Document doc) throws Exception {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorderWidthBottom(1.5f);
        c.setBorderColor(new Color(0x22, 0x22, 0x22));
        c.setBorderWidthTop(0); c.setBorderWidthLeft(0); c.setBorderWidthRight(0);
        c.setPaddingBottom(3);
        t.addCell(c);
        doc.add(t);
    }

    private static PdfPCell cellNoBorder(int align) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(6);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static Paragraph para(String text, Font font, int align) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(align);
        return p;
    }

    /** Meta key-value row: EN=[label|value], AR=[value|label] physical order. */
    private static void addMetaRow(PdfPTable tbl, String labelTxt, String valueTxt,
                                   boolean ar, Font labelFont, Font valueFont) {
        String lShaped = ar ? t(labelTxt, true) : labelTxt;
        if (ar) {
            metaCell(tbl, valueTxt, valueFont, Element.ALIGN_LEFT);
            metaCell(tbl, lShaped,  labelFont, Element.ALIGN_RIGHT);
        } else {
            metaCell(tbl, lShaped,  labelFont, Element.ALIGN_LEFT);
            metaCell(tbl, valueTxt, valueFont, Element.ALIGN_LEFT);
        }
    }

    private static void metaCell(PdfPTable tbl, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2);
        c.setHorizontalAlignment(align);
        tbl.addCell(c);
    }

    private static void hdrCellBg(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(C_HDR_BG);
        c.setBorderColor(C_LINE);
        c.setBorderWidthBottom(1.5f);
        c.setBorderWidthTop(0.5f); c.setBorderWidthLeft(0); c.setBorderWidthRight(0);
        c.setPadding(5);
        c.setHorizontalAlignment(align);
        table.addCell(c);
    }

    private static void dataCell(PdfPTable table, String text, Font font, int align, boolean altRow) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBorderColor(C_LINE);
        c.setBorderWidthBottom(0.5f);
        c.setBorderWidthTop(0); c.setBorderWidthLeft(0); c.setBorderWidthRight(0);
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        if (altRow) c.setBackgroundColor(C_ROW_ALT);
        table.addCell(c);
    }

    /**
     * Summary row: single bg color applied to BOTH cells (label and value).
     * AR swaps physical cell order to [value | label].
     */
    private static void sumRow(PdfPTable table,
                               String label, String value,
                               Font labelFont, Font valueFont,
                               int labelAlign, int valueAlign,
                               boolean topBorder, boolean dueBorder,
                               Color bg, boolean ar) {
        PdfPCell lc = new PdfPCell(new Phrase(label, labelFont));
        lc.setBorderColor(C_LINE2);
        lc.setBorderWidthBottom(dueBorder ? 1.5f : 0.5f);
        lc.setBorderWidthTop(topBorder || dueBorder ? 1.5f : 0f);
        lc.setBorderWidthLeft(0); lc.setBorderWidthRight(0);
        lc.setPadding(5);
        lc.setHorizontalAlignment(labelAlign);
        if (bg != null) lc.setBackgroundColor(bg);

        PdfPCell vc = new PdfPCell(new Phrase(value, valueFont));
        vc.setBorderColor(C_LINE2);
        vc.setBorderWidthBottom(dueBorder ? 1.5f : 0.5f);
        vc.setBorderWidthTop(topBorder || dueBorder ? 1.5f : 0f);
        vc.setBorderWidthLeft(0); vc.setBorderWidthRight(0);
        vc.setPadding(5);
        vc.setHorizontalAlignment(valueAlign);
        if (bg != null) vc.setBackgroundColor(bg);

        // AR: physical order [value | label] so value appears LEFT, label RIGHT
        if (ar) {
            table.addCell(vc);
            table.addCell(lc);
        } else {
            table.addCell(lc);
            table.addCell(vc);
        }
    }

    // ── Text + amount helpers ─────────────────────────────────────────────────────

    private static String t(String text, boolean ar) {
        if (!ar || text == null || text.isEmpty()) return text;
        try {
            String shaped = new ArabicShaping(ArabicShaping.LETTERS_SHAPE).shape(text);
            Bidi bidi = new Bidi();
            bidi.setPara(shaped, Bidi.RTL, null);
            return bidi.writeReordered(Bidi.DO_MIRRORING | Bidi.INSERT_LRM_FOR_NUMERIC);
        } catch (ArabicShapingException e) {
            return text;
        }
    }

    static boolean hasArabic(String text) {
        if (text == null) return false;
        for (char c : text.toCharArray()) {
            if ((c >= '؀' && c <= 'ۿ') ||   // Arabic block
                (c >= 'ﭐ' && c <= '﷿') ||   // Arabic Presentation Forms-A
                (c >= 'ﹰ' && c <= 'ﻼ')) {   // Arabic Presentation Forms-B (ICU4J shaping output)
                return true;
            }
        }
        return false;
    }

    private static String localName(ReceiptInfo info, boolean ar) {
        if (info == null) return null;
        String primary  = ar ? info.nameAr() : info.nameEn();
        String fallback = ar ? info.nameEn() : info.nameAr();
        return primary != null ? primary : fallback;
    }

    /**
     * Build footer as separate segments, each drawn with per-segment font selection.
     * Arabic labels are shaped; Latin identifiers (VAT/CR numbers) stay raw so they
     * draw with Helvetica rather than the Arabic font.
     *
     * AR order is reversed physically (CR left → VAT → store name right) so that
     * reading right-to-left gives the natural Arabic order: company → VAT → CR.
     */
    private static String[] buildFooterParts(ReceiptInfo info, String storeName, boolean ar) {
        List<String> parts = new ArrayList<>();
        if (!ar) {
            if (storeName != null && !storeName.isBlank()) parts.add(storeName);
            if (info != null && info.vatNumber() != null) {
                parts.add("VAT:");
                parts.add(info.vatNumber());
            }
            if (info != null && info.crNumber() != null) {
                parts.add("CR:");
                parts.add(info.crNumber());
            }
        } else {
            // Reversed: CR leftmost, store name rightmost → RTL reading = store, VAT, CR
            if (info != null && info.crNumber() != null) {
                parts.add(info.crNumber());
                parts.add(t("س.ت:", true));
            }
            if (info != null && info.vatNumber() != null) {
                parts.add(info.vatNumber());
                parts.add(t("ض.ق.م:", true));
            }
            if (storeName != null && !storeName.isBlank()) {
                parts.add(t(storeName, true));
            }
        }
        return parts.toArray(new String[0]);
    }

    private static BigDecimal paidAmount(OrderResponse order) {
        return "PAID".equals(order.status()) && order.total() != null
            ? order.total() : BigDecimal.ZERO;
    }

    private static BigDecimal itemTax(BigDecimal lineTotal, BigDecimal taxRate) {
        if (lineTotal == null || taxRate == null) return BigDecimal.ZERO;
        return lineTotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
    }

    private static String taxPercent(BigDecimal taxRate) {
        if (taxRate == null) return "0";
        return taxRate.multiply(BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString();
    }

    private static String fmtAmt(BigDecimal amount, String currency) {
        String num = amount != null ? amount.toPlainString() : "0.00";
        return currency != null ? num + " " + currency.toUpperCase() : num;
    }

    private static BaseFont loadFont(String filename) throws Exception {
        try (InputStream is = InvoicePdfRenderer.class.getClassLoader()
                .getResourceAsStream("fonts/" + filename)) {
            if (is == null) throw new IllegalStateException("Font not found: fonts/" + filename);
            byte[] bytes = is.readAllBytes();
            return BaseFont.createFont(filename, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, bytes, null);
        }
    }
}
