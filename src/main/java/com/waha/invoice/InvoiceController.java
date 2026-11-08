package com.waha.invoice;

import com.waha.order.OrderNotFoundException;
import com.waha.order.OrderService;
import com.waha.order.dto.OrderResponse;
import com.waha.store.StoreRepository;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final OrderService orderService;
    private final ReceiptInfoRepository receiptInfoRepository;
    private final StoreRepository storeRepository;

    public InvoiceController(OrderService orderService, ReceiptInfoRepository receiptInfoRepository,
                              StoreRepository storeRepository) {
        this.orderService = orderService;
        this.receiptInfoRepository = receiptInfoRepository;
        this.storeRepository = storeRepository;
    }

    // HTML invoice. ?ref=display shows Order #N, ?ref=uuid shows UUID only,
    // default ("both") shows display_id prominently with UUID as small ref.
    // No auth — orderId (UUID v7) is the access credential.
    //
    // When the payment gateway redirects back here after a completed payment,
    // it appends its own query params:
    //   MyFatoorah: ?paymentId=X&Id=X
    //   Stripe:     ?session_id=cs_X
    // On detecting those, we call verifyRedirectCallback so the order is
    // marked PAID before the HTML is rendered — the Flutter poll then picks
    // it up on its next tick.
    @GetMapping(value = "/{orderId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> view(@PathVariable String orderId,
                                        @RequestParam(required = false) String ref,
                                        @RequestParam(defaultValue = "en") String lang,
                                        @RequestParam(required = false) String offline,
                                        @RequestParam(required = false) Long since,
                                        @RequestParam(required = false) String paymentId,
                                        @RequestParam(name = "Id", required = false) String myfatoorahId,
                                        @RequestParam(name = "session_id", required = false) String stripeSessionId) {
        // Any payment param signals a return from the payment gateway; verify using
        // our stored provider reference (not the URL param — they differ for MyFatoorah).
        if (paymentId != null || myfatoorahId != null || stripeSessionId != null) {
            orderService.verifyRedirectCallback(orderId);
        }

        try {
            OrderResponse order = orderService.getOrderForInvoice(orderId);
            ReceiptInfo receiptInfo = receiptInfoRepository.findByStoreId(order.storeId()).orElse(null);
            return ResponseEntity.ok(InvoiceHtmlRenderer.render(order, receiptInfo, ref, lang));
        } catch (OrderNotFoundException e) {
            // A kiosk that made the order offline marks its QR link with offline=1: wait for the push, do not fail.
            // Without the marker an unknown order is a plain 404.
            if ("1".equals(offline)
                && orderId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
                boolean ar = "ar".equalsIgnoreCase(lang);
                return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(waitingPage(ar, since, java.time.Instant.now().getEpochSecond()));
            }
            return ResponseEntity.status(404).body("<!DOCTYPE html><html><body><h1>Invoice not found</h1></body></html>");
        }
    }

    static final long WAIT_LIMIT_SECONDS = 30 * 60;

    // Shown for an offline order the server does not have yet. It reloads itself every 10 seconds (keeping the
    // marker and the time it started waiting) and gives up after 30 minutes.
    static String waitingPage(boolean ar, Long since, long now) {
        long start = since == null || since > now ? now : since;
        boolean expired = now - start > WAIT_LIMIT_SECONDS;
        String head = "<!DOCTYPE html><html lang=\"" + (ar ? "ar" : "en") + "\" dir=\"" + (ar ? "rtl" : "ltr") + "\"><head>"
            + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + (expired ? "" : "<meta http-equiv=\"refresh\" content=\"10;url=?offline=1&amp;lang=" + (ar ? "ar" : "en")
                + "&amp;since=" + start + "\">")
            + "<title>Invoice</title></head>"
            + "<body style=\"font-family:sans-serif;text-align:center;padding:3rem 1rem;color:#444\">";
        if (expired) {
            return head + "<h2>" + (ar ? "تعذّر العثور على الفاتورة" : "We couldn't find this invoice") + "</h2>"
                + "<p>" + (ar ? "إذا دفعت للتو، جرّب مرة أخرى لاحقاً أو اسأل المتجر."
                              : "If you just paid, try again later or ask the store.") + "</p></body></html>";
        }
        return head + "<h2>" + (ar ? "جارٍ تجهيز فاتورتك" : "Your invoice is being prepared") + "</h2>"
            + "<p>" + (ar ? "ستظهر الفاتورة هنا تلقائياً خلال لحظات. لا تغلق هذه الصفحة."
                          : "It will appear here automatically in a moment. You can keep this page open.") + "</p>"
            + "</body></html>";
    }

    // PDF invoice. ?lang=ar renders bilingual with Arabic font; default is English-only.
    @GetMapping("/{orderId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String orderId,
                                       @RequestParam(defaultValue = "en") String lang) {
        try {
            OrderResponse order = orderService.getOrderForInvoice(orderId);
            ReceiptInfo receiptInfo = receiptInfoRepository.findByStoreId(order.storeId()).orElse(null);
            byte[] pdfBytes = InvoicePdfRenderer.render(order, receiptInfo, lang);
            String filename = "invoice-" + (order.displayId() != null ? order.displayId() : orderId) + ".pdf";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).build();
        }
    }
}
