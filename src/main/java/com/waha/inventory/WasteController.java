package com.waha.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.audit.AuditService;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.SheetFiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.sql.Timestamp;
import java.util.*;

// Inventory wastes: stock taken out because of expiry, damage and so on. Needs VIEW_WASTES to read and export,
// CREATE_WASTES to record (one by one or by import: the dashboard reads the file, checks it and calls the create call).
@RestController
@RequestMapping("/api/admin/inventory/wastes")
public class WasteController {

    private static final List<String> EXPORT_COLUMNS = List.of("Waste no.", "Date", "Branch", "Reason", "Employee",
        "Created by", "Notes", "Barcode", "Product (EN)", "Product (AR)", "Quantity");

    private final SessionService sessionService;
    private final WasteRepository repo;
    private final AuditService audit;

    public WasteController(SessionService sessionService, WasteRepository repo, AuditService audit) {
        this.sessionService = sessionService;
        this.repo = repo;
        this.audit = audit;
    }

    @GetMapping("/reasons")
    public ResponseEntity<?> reasons(@RequestHeader(value = "Authorization", required = false) String auth) {
        allowedStores(userSession(auth), Permission.VIEW_WASTES, Permission.CREATE_WASTES);
        List<Map<String, Object>> out = new ArrayList<>();
        for (WasteRules.Reason r : WasteRules.REASONS) out.add(Map.of("code", r.code(), "nameEn", r.nameEn(), "nameAr", r.nameAr()));
        return ResponseEntity.ok(out);
    }

    @GetMapping
    public ResponseEntity<?> list(@RequestHeader(value = "Authorization", required = false) String auth,
                                  @RequestParam(required = false) Long branchId, @RequestParam(required = false) String reason,
                                  @RequestParam(required = false) Long employeeId, @RequestParam(required = false) Long productId,
                                  @RequestParam(required = false) String search, @RequestParam(required = false) String from,
                                  @RequestParam(required = false) String to,
                                  @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        UserSession s = userSession(auth);
        WasteRepository.Filters f;
        try {
            f = filters(s, branchId, reason, employeeId, productId, search, from, to);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        size = Math.max(1, Math.min(size, 200));
        page = Math.max(0, page);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", repo.list(f, page, size));
        out.put("totalCount", repo.count(f));
        out.put("page", page);
        out.put("size", size);
        out.put("summary", repo.summary(f));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth, @PathVariable long id) {
        UserSession s = userSession(auth);
        Set<Long> stores = allowedStores(s, Permission.VIEW_WASTES);
        return repo.find(id, s.organizationId(), stores).<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("Waste not found")));
    }

    // Body: { branchId, reason, items: [{productId, quantity}], date?, employeeId?, notes?, clientRef? }.
    // clientRef (a UUID the caller makes) makes a retry safe: saving the same clientRef again returns the first waste.
    @PostMapping
    public ResponseEntity<?> create(@RequestHeader(value = "Authorization", required = false) String auth, @RequestBody JsonNode body) {
        UserSession s = userSession(auth);
        long org = s.organizationId();
        if (org <= 0) return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));

        long branchId = body.path("branchId").asLong(0);
        if (branchId <= 0) return ResponseEntity.badRequest().body(new ErrorResponse("branchId is required"));
        sessionService.requirePermission(auth, Permission.CREATE_WASTES, branchId);

        WasteRepository.StoreInfo store = repo.store(branchId).orElse(null);
        if (store == null || store.organizationId() != org) return ResponseEntity.badRequest().body(new ErrorResponse("Unknown branch: " + branchId));
        if (!store.active()) return ResponseEntity.badRequest().body(new ErrorResponse("This branch is not active"));

        String reason;
        Map<Long, Integer> lines;
        Instant when;
        String notes, clientRef;
        try {
            reason = WasteRules.reason(text(body, "reason"));
            lines = WasteRules.lines(body.get("items"));
            when = WasteRules.when(text(body, "date"), Instant.now());
            notes = WasteRules.notes(text(body, "notes"));
            clientRef = WasteRules.clientRef(text(body, "clientRef"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        Long employeeId = body.hasNonNull("employeeId") ? body.get("employeeId").asLong(0) : null;
        if (employeeId != null && (employeeId <= 0 || !repo.employeeInOrg(employeeId, org)))
            return ResponseEntity.badRequest().body(new ErrorResponse("Unknown employee: " + body.get("employeeId").asText()));
        Set<Long> known = repo.productsInOrg(lines.keySet(), org);
        for (Long pid : lines.keySet())
            if (!known.contains(pid)) return ResponseEntity.badRequest().body(new ErrorResponse("Unknown product: " + pid));

        long result = repo.create(new WasteRepository.NewWaste(org, branchId, reason, when, employeeId, notes, clientRef,
            s.userId(), audit.usernameOf(s.userId()), lines));
        boolean alreadySaved = result < 0;
        long id = Math.abs(result);
        Map<String, Object> detail = new LinkedHashMap<>(repo.find(id, org, null).orElseThrow());
        if (alreadySaved) {
            detail.put("alreadySaved", true);
            return ResponseEntity.ok(detail);
        }
        audit.record(AuditService.ORGANIZATION, org, "WASTE", String.valueOf(id), s.userId(), null, "WASTE_CREATED",
            "#" + id, null, null, lines.size() + " item(s), " + detail.get("quantity") + " unit(s), " + reason);
        return ResponseEntity.status(201).body(detail);
    }

    // The filtered list as a file, one row per waste line. format = csv (default) or xlsx.
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(defaultValue = "csv") String format,
                                    @RequestParam(required = false) Long branchId, @RequestParam(required = false) String reason,
                                    @RequestParam(required = false) Long employeeId, @RequestParam(required = false) Long productId,
                                    @RequestParam(required = false) String search, @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        UserSession s = userSession(auth);
        WasteRepository.Filters f;
        try {
            f = filters(s, branchId, reason, employeeId, productId, search, from, to);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        List<List<String>> rows = repo.exportRows(f);
        boolean xlsx = "xlsx".equalsIgnoreCase(format);
        byte[] data = xlsx ? SheetFiles.xlsx("Wastes", EXPORT_COLUMNS, rows) : SheetFiles.csv(EXPORT_COLUMNS, rows);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"wastes." + (xlsx ? "xlsx" : "csv") + "\"")
            .contentType(xlsx ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .body(data);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WasteRepository.Filters filters(UserSession s, Long branchId, String reason, Long employeeId, Long productId,
                                            String search, String from, String to) {
        Set<Long> stores = allowedStores(s, Permission.VIEW_WASTES);
        if (branchId != null && stores != null && !stores.contains(branchId))
            throw new ForbiddenException("Permission denied: VIEW_WASTES");
        String r = reason == null || reason.isBlank() ? null : WasteRules.reason(reason);
        Timestamp f = from == null || from.isBlank() ? null : day(from, 0);
        Timestamp t = to == null || to.isBlank() ? null : day(to, 1);
        String q = search == null || search.isBlank() ? null : search.trim();
        return new WasteRepository.Filters(s.organizationId(), stores, branchId, r, employeeId, productId, q, f, t);
    }

    private static Timestamp day(String s, int plusDays) {
        try {
            return Timestamp.from(LocalDate.parse(s.trim()).plusDays(plusDays).atStartOfDay().toInstant(ZoneOffset.UTC));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("from and to must be dates like 2026-11-17");
        }
    }

    private UserSession userSession(String auth) {
        UserSession s = sessionService.requireSession(auth);
        if (!s.isUserSession()) throw new ForbiddenException("Permission denied");
        return s;
    }

    private boolean superAdmin(UserSession s) {
        return sessionService.resolvePermissions(s.userId(), null).contains(Permission.MANAGE_SYSTEM.name());
    }

    // The branches where the caller holds at least one of the permissions; null = every branch (super admin).
    private Set<Long> allowedStores(UserSession s, Permission... anyOf) {
        if (superAdmin(s)) return null;
        Set<Long> out = new HashSet<>();
        for (long id : repo.storeIds(s.organizationId())) {
            Set<String> perms = sessionService.resolvePermissions(s.userId(), id);
            for (Permission p : anyOf) if (perms.contains(p.name())) { out.add(id); break; }
        }
        if (out.isEmpty()) throw new ForbiddenException("Permission denied: " + anyOf[0].name());
        return out;
    }

    private static String text(JsonNode b, String f) {
        JsonNode n = b.get(f);
        return n == null || n.isNull() ? null : n.asText();
    }
}
