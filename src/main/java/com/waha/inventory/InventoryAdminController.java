package com.waha.inventory;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ForbiddenException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/inventory")
public class InventoryAdminController {

    private final InventoryAdminRepository repo;
    private final SessionService sessionService;

    public InventoryAdminController(InventoryAdminRepository repo, SessionService sessionService) {
        this.repo = repo;
        this.sessionService = sessionService;
    }

    @GetMapping("/visits")
    public ResponseEntity<?> visits(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(required = false) Long   employeeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        UserSession session = requireAdminSession(auth);
        size = Math.max(1, Math.min(size, 100));
        var f = new InventoryAdminRepository.VisitFilters(session.organizationId(), branchId, employeeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items",      repo.listVisits(f, page, size));
        out.put("totalCount", repo.countVisits(f));
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/transfers")
    public ResponseEntity<?> transfers(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) Long   employeeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        UserSession session = requireAdminSession(auth);
        size = Math.max(1, Math.min(size, 100));
        var f = new InventoryAdminRepository.MovementFilters(
            session.organizationId(), "TRANSFER", branchId, productId, employeeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items",      repo.listMovements(f, page, size));
        out.put("totalCount", repo.countMovements(f));
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/returns")
    public ResponseEntity<?> returns(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) Long   employeeId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        UserSession session = requireAdminSession(auth);
        size = Math.max(1, Math.min(size, 100));
        var f = new InventoryAdminRepository.MovementFilters(
            session.organizationId(), "RETURN", branchId, productId, employeeId, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items",      repo.listMovements(f, page, size));
        out.put("totalCount", repo.countMovements(f));
        out.put("page",       page);
        out.put("size",       size);
        return ResponseEntity.ok(out);
    }

    @GetMapping("/stock")
    public ResponseEntity<?> stock(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "BRANCHES") String scope,
            @RequestParam(required = false) Long   branchId,
            @RequestParam(required = false) Long   productId,
            @RequestParam(required = false) Long   categoryId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {
        UserSession session = requireAdminSession(auth);
        size = Math.max(1, Math.min(size, 100));
        var f = new InventoryAdminRepository.StockFilters(session.organizationId(), branchId, productId, categoryId);
        Map<String, Object> out = new LinkedHashMap<>();
        if ("COMPANY".equalsIgnoreCase(scope)) {
            out.put("items",      repo.listStockCompany(f, page, size));
            out.put("totalCount", repo.countStockCompany(f));
        } else {
            out.put("items",      repo.listStockBranches(f, page, size));
            out.put("totalCount", repo.countStockBranches(f));
        }
        out.put("scope", scope.toUpperCase());
        out.put("page",  page);
        out.put("size",  size);
        return ResponseEntity.ok(out);
    }

    private UserSession requireAdminSession(String auth) {
        UserSession session = sessionService.requireSession(auth);
        var perms = sessionService.resolveSessionPermissions(session, 1L);
        if (!perms.contains(Permission.VIEW_ALL_ORDERS.name())) {
            throw new ForbiddenException("Permission denied: VIEW_ALL_ORDERS");
        }
        return session;
    }
}
