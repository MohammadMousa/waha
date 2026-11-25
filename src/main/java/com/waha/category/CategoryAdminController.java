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

    // Creates a category in the caller's organization (a super-admin picks one with X-Organization-Id).
    // Categories belong to the organization, not to a store, so every store of it gets the new one through sync.
    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody JsonNode body) {
        var session = sessionService.requireSession(auth);
        long org = session.organizationId();
        if (org <= 0) {
            return ResponseEntity.status(409).body(java.util.Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
        }
        sessionService.requirePermission(auth, Permission.MANAGE_CATEGORIES, org);

        String nameJson = nameJson(body.get("name"));
        if (nameJson == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name needs a non-empty ar or en text (max 100 characters each)"));
        }
        Long image = null;
        JsonNode img = body.get("imageResourceId");
        if (img != null && !img.isNull()) {
            if (!img.canConvertToLong() || !img.isIntegralNumber() || !categoryRepository.resourceExists(img.longValue())) {
                return ResponseEntity.badRequest().body(new ErrorResponse("imageResourceId does not exist"));
            }
            image = img.longValue();
        }
        long id = categoryRepository.create(org, nameJson, image);
        return ResponseEntity.status(201).body(categoryRepository.findById(id).orElseThrow());
    }

    // Keeps only the ar and en texts; null when the name is not an object or both texts are empty or too long.
    static String nameJson(JsonNode name) {
        if (name == null || !name.isObject()) return null;
        var out = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        for (String lang : new String[]{"ar", "en"}) {
            JsonNode t = name.get(lang);
            String v = t == null || !t.isTextual() ? "" : t.asText().trim();
            if (v.length() > 100) return null;
            out.put(lang, v);
        }
        if (out.get("ar").asText().isEmpty() && out.get("en").asText().isEmpty()) return null;
        return out.toString();
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
        long permStoreId = cat.companyId();
        sessionService.requirePermission(auth, Permission.MANAGE_CATEGORIES, permStoreId);

        categoryRepository.patch(id, body);
        return ResponseEntity.ok().build();
    }

    // Only an empty category can be deleted. It is a real delete (devices learn about it through the removals feed).
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        var opt = categoryRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Category not found: " + id));
        }
        sessionService.requirePermission(auth, Permission.MANAGE_CATEGORIES, opt.get().companyId());
        long n = categoryRepository.productCount(id);
        if (n > 0) {
            return ResponseEntity.status(409).body(new ErrorResponse(
                "Category has " + n + (n == 1 ? " product" : " products") + ", move or delete them first"));
        }
        categoryRepository.delete(id);
        return ResponseEntity.ok().build();
    }
}
