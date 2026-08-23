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

import java.util.List;

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
                                        @RequestParam(required = false) String paymentId,
                                        @RequestParam(name = "Id", required = false) String myfatoorahId,
                                        @RequestParam(name = "session_id", required = false) String stripeSessionId) {
        // Any payment param signals a return from the payment gateway; verify using
        // our stored provider reference (not the URL param — they differ for MyFatoorah).
        if (paymentId != null || myfatoorahId != null || stripeSessionId != null) {
            orderService.verifyRedirectCallback(orderId);
        }

        try {
            OrderResponse order = orderService.getOrder(orderId);
            List<Long> scopeChain = storeRepository.resolveScopeChain(order.storeId());
            ReceiptInfo receiptInfo = receiptInfoRepository.findByStoreChain(scopeChain).orElse(null);
            return ResponseEntity.ok(InvoiceHtmlRenderer.render(order, receiptInfo, ref));
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).body("<!DOCTYPE html><html><body><h1>Invoice not found</h1></body></html>");
        }
    }

    // PDF invoice. ?lang=ar renders bilingual with Arabic font; default is English-only.
    @GetMapping("/{orderId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String orderId,
                                       @RequestParam(defaultValue = "en") String lang) {
        try {
            OrderResponse order = orderService.getOrder(orderId);
            List<Long> scopeChain = storeRepository.resolveScopeChain(order.storeId());
            ReceiptInfo receiptInfo = receiptInfoRepository.findByStoreChain(scopeChain).orElse(null);
            byte[] pdfBytes = InvoicePdfRenderer.render(order, receiptInfo, lang);
            String filename = "invoice-" + (order.displayId() != null ? order.displayId() : orderId) + ".pdf";
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
        } catch (OrderNotFoundException e) {
            return ResponseEntity.status(404).build();
        }
    }
}
