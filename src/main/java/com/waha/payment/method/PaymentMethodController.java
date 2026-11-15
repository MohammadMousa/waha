package com.waha.payment.method;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/payment-methods")
public class PaymentMethodController {

    private static final List<String> VALID_MODES = List.of("NORMAL", "KIOSK", "SHOPPING");

    private final PaymentMethodRepository paymentMethodRepository;
    private final SessionService sessionService;

    public PaymentMethodController(PaymentMethodRepository paymentMethodRepository, SessionService sessionService) {
        this.paymentMethodRepository = paymentMethodRepository;
        this.sessionService = sessionService;
    }

    // Returns the payment methods available at a store for a given mode.
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader,
                                   @RequestParam(required = false) String mode) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }
        if (mode == null || mode.isBlank()) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode is required (NORMAL, KIOSK, or SHOPPING)"));
        }
        String upperMode = mode.toUpperCase();
        if (!VALID_MODES.contains(upperMode)) {
            return ResponseEntity.status(400).body(new ErrorResponse("mode must be one of: NORMAL, KIOSK, SHOPPING"));
        }
        List<PaymentMethodSummary> methods = paymentMethodRepository.findForStore(resolvedStoreId, upperMode);
        return ResponseEntity.ok(methods);
    }

    // Admin: all globally-active methods for a store with their per-store
    // enabled state. Requires MANAGE_STORES permission at the target store.
    @GetMapping("/admin")
    public ResponseEntity<?> adminList(@RequestParam(required = false) Long storeId,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("storeId is required"));
        }
        try {
            sessionService.requirePermission(authHeader, Permission.MANAGE_PAYMENT_METHODS, resolvedStoreId);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(paymentMethodRepository.findAllForAdmin(resolvedStoreId));
    }

    // Admin: enable or disable a payment method for the current store.
    // Upserts a row in payment_methods_store. Requires MANAGE_STORES.
    @PutMapping("/{id}/store-active")
    public ResponseEntity<?> setStoreActive(@PathVariable long id,
                                             @RequestParam(required = false) Long storeId,
                                             @RequestParam boolean active,
                                             @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("storeId is required"));
        }
        try {
            sessionService.requirePermission(authHeader, Permission.MANAGE_PAYMENT_METHODS, resolvedStoreId);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        paymentMethodRepository.setStoreActive(id, resolvedStoreId, active);
        return ResponseEntity.ok().build();
    }
    // Organization owner or super admin only: payment_methods has no company column, so this list and the switch below
    // affect every company on the server.
    private void requireOwner(String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        if (!session.isUserSession()
                || !sessionService.isOwnerOrSuperAdmin(session.userId())) {
            throw new ForbiddenException("Permission denied: organization owner or super admin only");
        }
    }

    // Admin: every payment method, including the ones switched off globally. active = the global flag.
    @GetMapping("/admin/all")
    public ResponseEntity<?> adminListAll(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            requireOwner(authHeader);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        return ResponseEntity.ok(paymentMethodRepository.findAllGlobal());
    }

    // Admin: switch one payment method on or off for the whole system (payment_methods.active).
    @PutMapping("/{id}/active")
    public ResponseEntity<?> setGlobalActive(@PathVariable long id,
                                              @RequestParam boolean active,
                                              @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            requireOwner(authHeader);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        if (!paymentMethodRepository.setGlobalActive(id, active)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Payment method not found"));
        }
        return ResponseEntity.ok().build();
    }

    // Admin: set the icon of one payment method for the whole system (a resource id), or clear it by leaving resourceId out.
    @PutMapping("/{id}/icon")
    public ResponseEntity<?> setGlobalIcon(@PathVariable long id,
                                            @RequestParam(required = false) Long resourceId,
                                            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            requireOwner(authHeader);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        if (resourceId != null && !paymentMethodRepository.resourceExists(resourceId)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Resource not found"));
        }
        if (!paymentMethodRepository.setGlobalIcon(id, resourceId)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Payment method not found"));
        }
        return ResponseEntity.ok().build();
    }

    private static final List<String> PROVIDERS = List.of("REDIRECT", "TERMINAL", "SIMULATED", "QR_LINK", "PAYMENT_URL");
    private static final Set<String> PATCH_FIELDS = Set.of("displayName", "provider", "availableModes", "offlineCapable",
        "active", "sortOrder", "paymentUrl");

    private static ResponseEntity<?> bad(String message) {
        return ResponseEntity.status(400).body(new ErrorResponse(message));
    }

    // Admin: partial edit of one payment method for the whole system. Everything but key (and the icon, which has its own call).
    @PatchMapping("/{id}")
    public ResponseEntity<?> patchGlobal(@PathVariable long id,
                                          @RequestBody JsonNode body,
                                          @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            requireOwner(authHeader);
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
        var opt = paymentMethodRepository.findGlobal(id);
        if (opt.isEmpty()) return ResponseEntity.status(404).body(new ErrorResponse("Payment method not found"));
        if (body == null || !body.isObject() || body.isEmpty()) return bad("No fields to change");
        if (body.has("key")) return bad("key cannot be changed");
        var names = body.fieldNames();
        while (names.hasNext()) {
            String n = names.next();
            if (!PATCH_FIELDS.contains(n)) return bad("Unknown field: " + n);
        }
        var cur = opt.get();

        String nameJson;
        {
            JsonNode dn = body.has("displayName") ? body.get("displayName") : cur.displayName();
            if (body.has("displayName")) {
                if (dn == null || !dn.isObject() || !dn.path("ar").isTextual() || !dn.path("en").isTextual()
                    || dn.path("ar").asText().isBlank() || dn.path("en").asText().isBlank()) {
                    return bad("displayName needs a non-blank ar and en");
                }
            }
            nameJson = dn == null ? "{}" : dn.toString();
            if (body.has("displayName")) {
                var o = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                o.put("ar", dn.get("ar").asText().trim());
                o.put("en", dn.get("en").asText().trim());
                nameJson = o.toString();
            }
        }

        String provider = cur.provider();
        if (body.has("provider")) {
            provider = body.get("provider").asText("");
            if (!PROVIDERS.contains(provider)) return bad("provider must be one of " + String.join(", ", PROVIDERS));
        }

        List<String> modes = cur.availableModes();
        if (body.has("availableModes")) {
            JsonNode m = body.get("availableModes");
            if (!m.isArray() || m.isEmpty()) return bad("availableModes needs at least one of " + String.join(", ", VALID_MODES));
            List<String> picked = new ArrayList<>();
            for (JsonNode x : m) {
                if (!x.isTextual() || !VALID_MODES.contains(x.asText())) {
                    return bad("availableModes may only contain " + String.join(", ", VALID_MODES));
                }
                picked.add(x.asText());
            }
            modes = VALID_MODES.stream().filter(picked::contains).toList();
        }

        boolean offline = cur.offlineCapable();
        if (body.has("offlineCapable")) {
            if (!body.get("offlineCapable").isBoolean()) return bad("offlineCapable must be true or false");
            offline = body.get("offlineCapable").asBoolean();
        }
        boolean active = cur.active();
        if (body.has("active")) {
            if (!body.get("active").isBoolean()) return bad("active must be true or false");
            active = body.get("active").asBoolean();
        }
        int sort = cur.sortOrder();
        if (body.has("sortOrder")) {
            if (!body.get("sortOrder").isInt()) return bad("sortOrder must be a whole number");
            sort = body.get("sortOrder").asInt();
        }

        String url = cur.paymentUrl();
        if (body.has("paymentUrl")) {
            JsonNode u = body.get("paymentUrl");
            url = u.isNull() ? null : (u.isTextual() ? u.asText().trim() : "\u0000");
            if ("\u0000".equals(url)) return bad("paymentUrl must be text or null");
            if (url != null && url.isEmpty()) url = null;
        }
        if (url != null) {
            if (!"PAYMENT_URL".equals(provider)) return bad("paymentUrl is only used by the PAYMENT_URL provider");
            boolean ok;
            try {
                java.net.URI uri = java.net.URI.create(url);
                ok = url.length() <= 500 && uri.getHost() != null && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()));
            } catch (IllegalArgumentException e) {
                ok = false;
            }
            if (!ok) return bad("paymentUrl must be a full http or https address");
        } else if ("PAYMENT_URL".equals(provider)) {
            return bad("PAYMENT_URL needs a paymentUrl");
        }

        paymentMethodRepository.updateGlobal(id, nameJson, provider, String.join(",", modes), offline, active, sort, url);
        return ResponseEntity.ok(paymentMethodRepository.findGlobal(id).orElseThrow());
    }
}
