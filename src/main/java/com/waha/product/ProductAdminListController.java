package com.waha.product;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.pricing.VatPricing;
import com.waha.product.dto.ProductView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// The product list for the admin dashboard: every product of the organization, active or not, public or not,
// with search and filters. The public list for apps and kiosks is GET /api/products and is not changed.
@RestController
@RequestMapping("/api/admin/products")
public class ProductAdminListController {

    private static final int MAX_SIZE = 200;

    private final SessionService sessionService;
    private final ProductRepository products;
    private final VatPricing vatPricing;

    public ProductAdminListController(SessionService sessionService, ProductRepository products, VatPricing vatPricing) {
        this.sessionService = sessionService;
        this.products = products;
        this.vatPricing = vatPricing;
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(required = false) String search,
                                  @RequestParam(required = false) Long categoryId,
                                  @RequestParam(required = false) Boolean active,
                                  @RequestParam(required = false) Boolean publicListed,
                                  @RequestParam(required = false) String tag,
                                  @RequestParam(defaultValue = "0") int page,
                                  @RequestParam(defaultValue = "24") int size) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.VIEW_PRODUCTS, session.organizationId());
        long org = session.organizationId();
        if (org <= 0) {
            return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
        }
        int s = Math.min(Math.max(size, 1), MAX_SIZE);
        int pg = Math.max(page, 0);
        var result = products.adminList(org, search, categoryId, active, publicListed, tag, pg, s);
        BigDecimal rate = vatPricing.organizationVatRate(org);
        boolean withVat = vatPricing.showWithVat(org);
        List<ProductView> views = result.products().stream()
            .map(p -> ProductView.of(p, VatPricing.withVat(p.price(), rate), withVat)).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("products", views);
        body.put("page", pg);
        body.put("size", s);
        body.put("totalCount", result.totalCount());
        body.put("hasMore", (long) (pg + 1) * s < result.totalCount());
        return ResponseEntity.ok(body);
    }
}
