package com.waha.store;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/organization/info")
public class OrgInfoController {

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionService sessionService;

    public OrgInfoController(NamedParameterJdbcTemplate jdbc, SessionService sessionService) {
        this.jdbc = jdbc;
        this.sessionService = sessionService;
    }

    // ── GET /api/admin/organization/info ──────────────────────────────────────
    @GetMapping
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        try {
            UserSession session = require(auth);
            return ResponseEntity.ok(fetchInfo(session.organizationId()));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    // ── PATCH /api/admin/organization/info ────────────────────────────────────
    // Accepts any subset of {name, slug, vatRate, currency}. At least one field required.
    // vatRate is stored as a decimal fraction (0.15 = 15%). currency is ISO 4217 (e.g. SAR).
    record PatchRequest(String name, String slug, BigDecimal vatRate, String currency) {}

    @PatchMapping
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody PatchRequest body) {
        try {
            UserSession session = require(auth);
            long orgId = session.organizationId();

            boolean hasName     = body.name()     != null && !body.name().isBlank();
            boolean hasSlug     = body.slug()     != null && !body.slug().isBlank();
            boolean hasVatRate  = body.vatRate()  != null;
            boolean hasCurrency = body.currency() != null && !body.currency().isBlank();

            if (!hasName && !hasSlug && !hasVatRate && !hasCurrency) {
                return ResponseEntity.badRequest().body(new ErrorResponse("At least one field required: name, slug, vatRate, or currency"));
            }

            if (hasSlug) {
                String slug = body.slug().trim().toLowerCase();
                if (!slug.matches("[a-z0-9_-]+")) {
                    return ResponseEntity.badRequest().body(new ErrorResponse("slug must contain only lowercase letters, numbers, hyphens, or underscores"));
                }
                long taken = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM organizations WHERE slug = :slug AND id != :id",
                    Map.of("slug", slug, "id", orgId), Long.class);
                if (taken > 0) {
                    return ResponseEntity.status(409).body(new ErrorResponse("slug '" + slug + "' is already taken"));
                }
            }

            if (hasVatRate) {
                BigDecimal vr = body.vatRate();
                if (vr.compareTo(BigDecimal.ZERO) < 0 || vr.compareTo(BigDecimal.ONE) > 0) {
                    return ResponseEntity.badRequest().body(new ErrorResponse("vatRate must be between 0 and 1 (e.g. 0.15 for 15%)"));
                }
            }

            if (hasCurrency && body.currency().trim().length() != 3) {
                return ResponseEntity.badRequest().body(new ErrorResponse("currency must be a 3-letter ISO code (e.g. SAR, USD)"));
            }

            if (hasName) {
                jdbc.update("UPDATE organizations SET name = :v WHERE id = :id",
                    Map.of("v", body.name().trim(), "id", orgId));
            }
            if (hasSlug) {
                jdbc.update("UPDATE organizations SET slug = :v WHERE id = :id",
                    Map.of("v", body.slug().trim().toLowerCase(), "id", orgId));
            }
            if (hasVatRate) {
                jdbc.update("UPDATE organizations SET vat_rate = :v WHERE id = :id",
                    Map.of("v", body.vatRate(), "id", orgId));
                jdbc.update("UPDATE stores SET vat_rate = :v WHERE organization_id = :id",
                    Map.of("v", body.vatRate(), "id", orgId));
            }
            if (hasCurrency) {
                String cur = body.currency().trim().toUpperCase();
                jdbc.update("UPDATE organizations SET currency = :v WHERE id = :id",
                    Map.of("v", cur, "id", orgId));
                jdbc.update("UPDATE stores SET currency = :v WHERE organization_id = :id",
                    Map.of("v", cur, "id", orgId));
            }

            return ResponseEntity.ok(fetchInfo(orgId));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    private Map<String, Object> fetchInfo(long orgId) {
        return jdbc.queryForObject(
            "SELECT id, name, slug, vat_rate, currency, created_at FROM organizations WHERE id = :id",
            Map.of("id", orgId),
            (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",        rs.getLong("id"));
                m.put("name",      rs.getString("name"));
                m.put("slug",      rs.getString("slug"));
                m.put("vatRate",   rs.getBigDecimal("vat_rate"));
                m.put("currency",  rs.getString("currency"));
                m.put("createdAt", rs.getTimestamp("created_at").toInstant().toString());
                return m;
            });
    }

    private UserSession require(String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, session.storeId());
        return session;
    }
}
