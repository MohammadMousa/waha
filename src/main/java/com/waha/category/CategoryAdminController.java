package com.waha.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

// Admin edit endpoint for categories. Requires MANAGE_CATEGORIES permission.
@RestController
@RequestMapping("/api/categories")
public class CategoryAdminController {

    private final CategoryRepository categoryRepository;
    private final SessionService sessionService;

    public CategoryAdminController(CategoryRepository categoryRepository, SessionService sessionService) {
        this.categoryRepository = categoryRepository;
        this.sessionService = sessionService;
    }

    @PatchMapping("/{id}")
    public ResponseEntity<?> patch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id,
            @RequestBody JsonNode body) {

        var opt = categoryRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Category not found: " + id));
        }
        Category cat = opt.get();

        // Permission is checked against the category's own store, or root store for globals.
        long permStoreId = cat.scopeStoreId() != null ? cat.scopeStoreId() : 0L;
        sessionService.requirePermission(auth, Permission.MANAGE_CATEGORIES, permStoreId);

        categoryRepository.patch(id, body);
        return ResponseEntity.ok().build();
    }
}
