package com.waha.reports;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.product.ProductRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/reports")
public class ReportsController {

    private final ReportsRepository reportsRepository;
    private final SessionService sessionService;
    private final ProductRepository productRepository;

    public ReportsController(ReportsRepository reportsRepository, SessionService sessionService,
                             ProductRepository productRepository) {
        this.reportsRepository = reportsRepository;
        this.sessionService = sessionService;
        this.productRepository = productRepository;
    }

    // ── Filter dropdowns ──────────────────────────────────────────────────────

    @GetMapping("/stores")
    public ResponseEntity<?> stores(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(reportsRepository.getAllStores());
    }

    @GetMapping("/categories")
    public ResponseEntity<?> categories(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(reportsRepository.getAllCategories());
    }

    @GetMapping("/kiosks")
    public ResponseEntity<?> kiosks(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(reportsRepository.getKiosks());
    }

    @GetMapping("/products")
    public ResponseEntity<?> products(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String  search,
            @RequestParam(required = false) Long    categoryId,
            @RequestParam(required = false) Boolean active) {
        UserSession session = sessionService.requireSession(auth);
        return ResponseEntity.ok(productRepository.adminLookup(session.organizationId(), search, categoryId, active));
    }

    @GetMapping("/payment-methods")
    public ResponseEntity<?> paymentMethods(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(reportsRepository.getPaymentProviders());
    }

    // ── Orders report ─────────────────────────────────────────────────────────

    @GetMapping("/orders")
    public ResponseEntity<?> orders(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long    storeId,
            @RequestParam(required = false) String  status,
            @RequestParam(required = false) String  from,
            @RequestParam(required = false) String  to,
            @RequestParam(required = false) String  kiosk,
            @RequestParam(required = false) String  paymentType,
            @RequestParam(required = false) Boolean synced,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        if (size < 1 || size > 100) size = 10;

        try {
            if (from != null) LocalDate.parse(from);
            if (to   != null) LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid date format — use yyyy-MM-dd"));
        }

        ReportsRepository.OrderFilters f = new ReportsRepository.OrderFilters(
            storeId, status, from, to, kiosk, paymentType, synced);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",    reportsRepository.getOrdersSummary(f));
        out.put("items",      reportsRepository.getOrdersItems(f, page, size));
        out.put("totalCount", reportsRepository.getOrdersCount(f));
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }

    // ── Products Sales report ─────────────────────────────────────────────────

    @GetMapping("/products-sales")
    public ResponseEntity<?> productsSales(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long    storeId,
            @RequestParam(required = false) Long    categoryId,
            @RequestParam(required = false) Long    productId,
            @RequestParam(required = false) String  from,
            @RequestParam(required = false) String  to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);

        if (size < 1 || size > 100) size = 10;

        if (from == null) from = LocalDate.now().minusDays(30).toString();
        if (to   == null) to   = LocalDate.now().toString();

        try {
            LocalDate.parse(from);
            LocalDate.parse(to);
        } catch (DateTimeParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Invalid date format — use yyyy-MM-dd"));
        }

        ReportsRepository.Filters f = new ReportsRepository.Filters(storeId, categoryId, productId, from, to);

        Map<String, Object> summary = reportsRepository.getProductsSalesSummary(f);
        long totalCount             = reportsRepository.getProductsSalesCount(f);
        List<Map<String, Object>> items = reportsRepository.getProductsSalesItems(f, page, size);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary",    summary);
        out.put("items",      items);
        out.put("totalCount", totalCount);
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }
}
