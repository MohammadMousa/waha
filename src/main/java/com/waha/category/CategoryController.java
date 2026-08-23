package com.waha.category;

import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import com.waha.store.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {

    private final CategoryRepository categoryRepository;
    private final StoreRepository storeRepository;
    private final SessionService sessionService;

    public CategoryController(CategoryRepository categoryRepository, StoreRepository storeRepository, SessionService sessionService) {
        this.categoryRepository = categoryRepository;
        this.storeRepository = storeRepository;
        this.sessionService = sessionService;
    }

    // Customer-facing: public, active categories for the store's full scope
    // chain (global + any store-specific overrides). storeId resolves from
    // the session if not passed explicitly - same pattern as products.
    @GetMapping
    public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
                                   @RequestHeader(value = "Authorization", required = false) String authHeader) {
        Long resolvedStoreId = sessionService.resolveStoreId(storeId, authHeader);
        if (resolvedStoreId == null) {
            return ResponseEntity.status(400).body(new ErrorResponse(
                "storeId is required (pass it explicitly, or select a store first via POST /api/auth/store)"));
        }

        List<Long> scopeChain = storeRepository.resolveScopeChain(resolvedStoreId);
        List<Category> categories = categoryRepository.findForStore(scopeChain, true);
        return ResponseEntity.ok(categories);
    }
}
