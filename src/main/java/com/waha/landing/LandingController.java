package com.waha.landing;

import com.waha.auth.SessionService;
import com.waha.resource.ResourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Resolves dynamic landing pages stored in the resource library.
// Local store page overrides the global root page (id=0).
// Returns a resource URL + content hash — Flutter loads the URL, backend serves the HTML.
@RestController
@RequestMapping("/api/landing")
public class LandingController {

    private static final Set<String> VALID_KEYS = Set.of(
        "KIOSK_LANDING", "SHOPPING_LANDING", "CLIENT_LANDING", "ADMIN_LANDING"
    );
    private static final long ROOT_STORE_ID = 1L;
    private static final String PAGES_DIR = "pages";

    private final ResourceRepository resourceRepository;
    private final SessionService sessionService;

    public LandingController(ResourceRepository resourceRepository, SessionService sessionService) {
        this.resourceRepository = resourceRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/{pageKey}")
    public ResponseEntity<?> getLandingPage(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String pageKey) {

        if (!VALID_KEYS.contains(pageKey)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Unknown page key: " + pageKey));
        }

        Long sessionStoreId = sessionService.tryResolveSession(auth)
            .map(s -> s.storeId())
            .orElse(null);

        String assetName = pageKey + ".html";

        // 1. Try local store override.
        if (sessionStoreId != null && !sessionStoreId.equals(ROOT_STORE_ID)) {
            Optional<ResolvedPage> local = resolve(sessionStoreId, assetName);
            if (local.isPresent()) return ResponseEntity.ok(local.get().toResponse(pageKey, "local"));
        }

        // 2. Fall back to root store global default.
        Optional<ResolvedPage> global = resolve(ROOT_STORE_ID, assetName);
        if (global.isPresent()) return ResponseEntity.ok(global.get().toResponse(pageKey, "global"));

        return ResponseEntity.notFound().build();
    }

    private Optional<ResolvedPage> resolve(long storeId, String assetName) {
        Optional<Long> dirId = resourceRepository.findDirectoryId(storeId, PAGES_DIR);
        if (dirId.isEmpty()) return Optional.empty();

        Optional<Long> resourceId = resourceRepository.findAssetResourceId(storeId, dirId.get(), assetName);
        if (resourceId.isEmpty()) return Optional.empty();

        Optional<ResourceRepository.ResourceMeta> meta = resourceRepository.findMetaById(resourceId.get());
        if (meta.isEmpty()) return Optional.empty();

        String storeName = resourceRepository.findStoreNameById(storeId).orElse(String.valueOf(storeId));
        return Optional.of(new ResolvedPage(storeName, meta.get().sha256()));
    }

    private record ResolvedPage(String storeName, String sha256) {
        Map<String, Object> toResponse(String pageKey, String scope) {
            return Map.of(
                "page_key", pageKey,
                "scope", scope,
                "store", storeName,
                "resource_url", "/resource/" + storeName + "/" + PAGES_DIR + "/" + pageKey + ".html",
                "content_hash", sha256
            );
        }
    }
}
