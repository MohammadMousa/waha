package com.waha.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.InvalidRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryRepository repo;
    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public InventoryController(InventoryRepository repo, SessionService sessionService, ObjectMapper objectMapper) {
        this.repo = repo;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    // ── Branches ──────────────────────────────────────────────────────────────

    /**
     * GET /api/inventory/branches
     * Returns all active stores in the employee's organization.
     * The employee uses this list to pick a target branch for an operation.
     */
    @GetMapping("/branches")
    public ResponseEntity<?> listBranches(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        List<InventoryRepository.BranchView> branches = repo.listOrgBranches(session.organizationId());
        return ResponseEntity.ok(branches.stream().map(b -> {
            java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", b.id());
            m.put("name", b.name());
            m.put("displayName", parseJsonName(b.displayName()));
            return m;
        }).toList());
    }

    // ── Visits ────────────────────────────────────────────────────────────────

    /**
     * POST /api/inventory/visits
     * Starts a new inventory visit/activity for the authenticated employee.
     * The origin store is taken from the session's store context.
     */
    @PostMapping("/visits")
    public ResponseEntity<?> startVisit(
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        Object storeObj = body.get("storeId");
        if (storeObj == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("storeId is required"));
        }
        long storeId = Long.parseLong(storeObj.toString());
        long visitId = repo.createVisit(session.organizationId(), session.employeeId(), storeId);
        return ResponseEntity.ok(repo.findVisitById(visitId).map(this::visitResponse).orElseThrow());
    }

    /**
     * GET /api/inventory/visits/{visitId}
     * Returns a visit with all its operations and items.
     */
    @GetMapping("/visits/{visitId}")
    public ResponseEntity<?> getVisit(
            @PathVariable long visitId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        InventoryRepository.VisitRow visit = requireOwnedVisit(visitId, session);

        List<InventoryRepository.OperationRow> ops = repo.listOperations(visitId);
        List<Map<String, Object>> opMaps = ops.stream().map(op -> {
            List<Map<String, Object>> items = repo.listItems(op.id()).stream().map(item ->
                Map.<String, Object>of(
                    "id",          item.id(),
                    "productId",   item.productId(),
                    "productName", parseJsonName(item.productName()),
                    "quantity",    item.quantity()
                )
            ).toList();
            java.util.Map<String, Object> opMap = new java.util.LinkedHashMap<>();
            opMap.put("id",                     op.id());
            opMap.put("operationType",          op.operationType());
            opMap.put("targetStoreId",          op.targetStoreId());
            opMap.put("targetStoreName",        op.targetStoreName());
            opMap.put("targetStoreDisplayName", parseJsonName(op.targetStoreDisplayName()));
            opMap.put("notes",                  op.notes() != null ? op.notes() : "");
            opMap.put("createdAt",              op.createdAt());
            opMap.put("items",                  items);
            return opMap;
        }).toList();

        Map<String, Object> resp = Map.of(
            "id",           visit.id(),
            "storeId",      visit.storeId(),
            "storeName",    visit.storeName(),
            "status",       visit.status(),
            "createdAt",    visit.createdAt(),
            "completedAt",  visit.completedAt() != null ? visit.completedAt() : "",
            "operations",   opMaps
        );
        return ResponseEntity.ok(resp);
    }

    // ── Operations ────────────────────────────────────────────────────────────

    /**
     * POST /api/inventory/visits/{visitId}/operations
     * Body: { "operationType": "TRANSFER"|"RETURN", "targetStoreId": 123,
     *         "notes": "optional", "items": [{"productId": 1, "quantity": 3}] }
     * Adds an inventory operation to the visit. Items are required (at least one).
     */
    @PostMapping("/visits/{visitId}/operations")
    public ResponseEntity<?> addOperation(
            @PathVariable long visitId,
            @RequestBody Map<String, Object> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        InventoryRepository.VisitRow visit = requireOwnedActiveVisit(visitId, session);

        String opType = body.getOrDefault("operationType", "").toString().toUpperCase();
        if (!opType.equals("TRANSFER") && !opType.equals("RETURN")) {
            throw new InvalidRequestException("operationType must be TRANSFER or RETURN");
        }

        Object tsObj = body.get("targetStoreId");
        if (tsObj == null) throw new InvalidRequestException("targetStoreId is required");
        long targetStoreId = Long.parseLong(tsObj.toString());

        String notes = body.containsKey("notes") ? body.get("notes").toString() : null;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) body.get("items");
        if (items == null || items.isEmpty()) {
            throw new InvalidRequestException("items is required and must not be empty");
        }
        for (Map<String, Object> item : items) {
            int qty = Integer.parseInt(item.getOrDefault("quantity", "0").toString());
            if (qty <= 0) throw new InvalidRequestException("item quantity must be positive");
        }

        long opId = repo.addOperation(visitId, opType, targetStoreId, notes);
        for (Map<String, Object> item : items) {
            long productId = Long.parseLong(item.get("productId").toString());
            int  quantity  = Integer.parseInt(item.get("quantity").toString());
            repo.addItem(opId, productId, quantity);
        }

        InventoryRepository.OperationRow op = repo.findOperationById(opId).orElseThrow();
        List<Map<String, Object>> savedItems = repo.listItems(opId).stream().map(item ->
            Map.<String, Object>of(
                "id",        item.id(),
                "productId", item.productId(),
                "productName", parseJsonName(item.productName()),
                "quantity",  item.quantity()
            )
        ).toList();

        java.util.Map<String, Object> opResp = new java.util.LinkedHashMap<>();
        opResp.put("id",                     op.id());
        opResp.put("operationType",          op.operationType());
        opResp.put("targetStoreId",          op.targetStoreId());
        opResp.put("targetStoreName",        op.targetStoreName());
        opResp.put("targetStoreDisplayName", parseJsonName(op.targetStoreDisplayName()));
        opResp.put("notes",                  op.notes() != null ? op.notes() : "");
        opResp.put("createdAt",              op.createdAt());
        opResp.put("items",                  savedItems);
        return ResponseEntity.ok(opResp);
    }

    /**
     * DELETE /api/inventory/visits/{visitId}/operations/{operationId}
     * Removes an operation (and its items) from the visit.
     */
    @DeleteMapping("/visits/{visitId}/operations/{operationId}")
    public ResponseEntity<?> removeOperation(
            @PathVariable long visitId,
            @PathVariable long operationId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        requireOwnedActiveVisit(visitId, session);

        InventoryRepository.OperationRow op = repo.findOperationById(operationId)
            .orElseThrow(() -> new InvalidRequestException("Operation not found"));
        if (op.visitId() != visitId) throw new InvalidRequestException("Operation does not belong to this visit");

        repo.deleteOperation(operationId);
        return ResponseEntity.ok().build();
    }

    // ── Complete visit ────────────────────────────────────────────────────────

    /**
     * POST /api/inventory/visits/{visitId}/complete
     * Ends the visit and persists the full activity.
     * Atomically updates store_inventory for all operations and marks the visit COMPLETED.
     */
    @PostMapping("/visits/{visitId}/complete")
    public ResponseEntity<?> completeVisit(
            @PathVariable long visitId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = requireEmployeeSession(auth, Permission.PROCESS_INVENTORY);
        InventoryRepository.VisitRow visit = requireOwnedActiveVisit(visitId, session);

        List<InventoryRepository.OperationRow> ops = repo.listOperations(visitId);
        if (ops.isEmpty()) {
            throw new InvalidRequestException("Visit has no operations. Add at least one transfer or return before completing.");
        }
        for (InventoryRepository.OperationRow op : ops) {
            if (repo.listItems(op.id()).isEmpty()) {
                throw new InvalidRequestException("Operation " + op.id() + " has no items.");
            }
        }

        LocalDateTime completedAt = repo.completeVisit(visitId);

        return ResponseEntity.ok(Map.of(
            "id",          visitId,
            "status",      "COMPLETED",
            "completedAt", completedAt
        ));
    }

    // ── Inventory read ────────────────────────────────────────────────────────

    /**
     * GET /api/inventory/stock/{storeId}
     * Returns current stock levels for a store.
     * Requires VIEW_INVENTORY permission (employees with CASHIER or above can read).
     */
    @GetMapping("/stock/{storeId}")
    public ResponseEntity<?> getStoreStock(
            @PathVariable long storeId,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        UserSession session = sessionService.requireSession(auth);
        if (session.isEmployeeSession()) {
            var perms = sessionService.resolveEmployeePermissionsUnified(session.employeeId());
            if (!perms.contains(Permission.VIEW_INVENTORY.name())) {
                throw new ForbiddenException("Permission denied: VIEW_INVENTORY");
            }
        } else {
            sessionService.requirePermission(auth, Permission.VIEW_INVENTORY, storeId);
        }

        List<Map<String, Object>> levels = repo.listStoreInventory(storeId).stream().map(s ->
            Map.<String, Object>of("productId", s.productId(), "quantity", s.quantity())
        ).toList();
        return ResponseEntity.ok(levels);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private UserSession requireEmployeeSession(String auth, Permission permission) {
        UserSession session = sessionService.requireSession(auth);
        if (!session.isEmployeeSession()) {
            throw new ForbiddenException("This endpoint requires an employee session");
        }
        var perms = sessionService.resolveEmployeePermissionsUnified(session.employeeId());
        if (!perms.contains(permission.name())) {
            throw new ForbiddenException("Permission denied: " + permission.name());
        }
        return session;
    }

    private InventoryRepository.VisitRow requireOwnedVisit(long visitId, UserSession session) {
        InventoryRepository.VisitRow visit = repo.findVisitById(visitId)
            .orElseThrow(() -> new InvalidRequestException("Visit not found"));
        if (visit.employeeId() != session.employeeId()) {
            throw new ForbiddenException("Visit does not belong to this employee");
        }
        return visit;
    }

    private InventoryRepository.VisitRow requireOwnedActiveVisit(long visitId, UserSession session) {
        InventoryRepository.VisitRow visit = requireOwnedVisit(visitId, session);
        if (!"ACTIVE".equals(visit.status())) {
            throw new InvalidRequestException("Visit is not active (status: " + visit.status() + ")");
        }
        return visit;
    }

    private Map<String, Object> visitResponse(InventoryRepository.VisitRow v) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id",               v.id());
        m.put("storeId",          v.storeId());
        m.put("storeName",        v.storeName());
        m.put("storeDisplayName", parseJsonName(v.storeDisplayName()));
        m.put("status",           v.status());
        m.put("createdAt",        v.createdAt());
        return m;
    }

    private Object parseJsonName(String nameJson) {
        if (nameJson == null) return null;
        try {
            return objectMapper.readValue(nameJson, Object.class);
        } catch (Exception e) {
            return nameJson;
        }
    }
}
