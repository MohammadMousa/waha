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
            // A kiosk that was offline prints the QR before the order reaches the server: wait, do not fail.
            if (orderId.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
                boolean ar = "ar".equalsIgnoreCase(lang);
                return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(waitingPage(ar));
            }
            return ResponseEntity.status(404).body("<!DOCTYPE html><html><body><h1>Invoice not found</h1></body></html>");
        }
    }

    // Shown for an order the server does not have yet; refreshes itself until the order arrives.
    static String waitingPage(boolean ar) {
        return "<!DOCTYPE html><html lang=\"" + (ar ? "ar" : "en") + "\" dir=\"" + (ar ? "rtl" : "ltr") + "\"><head>"
            + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta http-equiv=\"refresh\" content=\"10\"><title>Invoice</title></head>"
            + "<body style=\"font-family:sans-serif;text-align:center;padding:3rem 1rem;color:#444\">"
            + "<h2>" + (ar ? "جارٍ تجهيز فاتورتك" : "Your invoice is being prepared") + "</h2>"
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
