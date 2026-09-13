package com.waha.landing;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.resource.ResourceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

// Resolves dynamic landing pages stored in the resource library.
// A branch's local page (if any) overrides its organization's global page
// (store_id IS NULL). Returns a resource URL + content hash — Flutter loads the
// URL, backend serves the HTML.
@RestController
@RequestMapping("/api/landing")
public class LandingController {

    private static final Set<String> VALID_KEYS = Set.of(
        "KIOSK_LANDING", "SHOPPING_LANDING", "CLIENT_LANDING", "ADMIN_LANDING"
    );
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
            @PathVariable String pageKey,
            @RequestParam(value = "storeId", required = false) Long explicitStoreId) {

        if (!VALID_KEYS.contains(pageKey)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Unknown page key: " + pageKey));
        }

        Optional<UserSession> session = sessionService.tryResolveSession(auth);

        // Prefer an explicit storeId (admin previewing one specific branch) over the
        // session's own store. Neither present -> no local override, go straight to
        // the org's global page.
        Long scopeStoreId = explicitStoreId != null ? explicitStoreId
            : session.map(UserSession::storeId).orElse(null);

        String assetName = pageKey + ".html";

        // 1. Local branch override, if the requested/session store has one.
        if (scopeStoreId != null) {
            Optional<ResolvedPage> local = resolveLocal(scopeStoreId, assetName);
            if (local.isPresent()) return ResponseEntity.ok(local.get().toResponse(pageKey, "local"));
        }

        // 2. Organization-level global default. The org comes from the authenticated
        // caller's own session where available (works even with no store in scope at
        // all — e.g. admin explicitly asking for global), falling back to the scoped
        // store's org otherwise.
        Long orgId = session.map(UserSession::organizationId)
            .orElseGet(() -> scopeStoreId != null
                ? resourceRepository.findOrgIdByStoreId(scopeStoreId).orElse(null)
                : null);
        if (orgId == null) return ResponseEntity.notFound().build();

        Optional<ResolvedPage> global = resolveGlobal(orgId, assetName);
        if (global.isPresent()) return ResponseEntity.ok(global.get().toResponse(pageKey, "global"));

        return ResponseEntity.notFound().build();
    }

    private Optional<ResolvedPage> resolveLocal(long storeId, String assetName) {
        Optional<Long> dirId = resourceRepository.findDirectoryId(storeId, PAGES_DIR);
        if (dirId.isEmpty()) return Optional.empty();

        Optional<Long> resourceId = resourceRepository.findAssetResourceId(dirId.get(), assetName);
        if (resourceId.isEmpty()) return Optional.empty();

        Optional<ResourceRepository.ResourceMeta> meta = resourceRepository.findMetaById(resourceId.get());
        if (meta.isEmpty()) return Optional.empty();

        String storeName = resourceRepository.findStoreNameById(storeId).orElse(String.valueOf(storeId));
        // Branch scope is always the 4-segment form — a branch keeps its own store_id
        // regardless of whether its name happens to coincide with the org's slug.
        String orgSlug = resourceRepository.findOrgSlugByStoreId(storeId).orElse(storeName);
        String resourceUrl = "/resource/" + orgSlug + "/" + storeName + "/" + PAGES_DIR + "/" + assetName;

        return Optional.of(new ResolvedPage(storeName, resourceUrl, meta.get().sha256()));
    }

    private Optional<ResolvedPage> resolveGlobal(long orgId, String assetName) {
        Optional<Long> dirId = resourceRepository.findDirectoryIdGlobal(orgId, PAGES_DIR);
        if (dirId.isEmpty()) return Optional.empty();

        Optional<Long> resourceId = resourceRepository.findAssetResourceId(dirId.get(), assetName);
        if (resourceId.isEmpty()) return Optional.empty();

        Optional<ResourceRepository.ResourceMeta> meta = resourceRepository.findMetaById(resourceId.get());
        if (meta.isEmpty()) return Optional.empty();

        String orgSlug = resourceRepository.findOrgSlugById(orgId).orElse(String.valueOf(orgId));
        String resourceUrl = "/resource/" + orgSlug + "/" + PAGES_DIR + "/" + assetName;

        return Optional.of(new ResolvedPage(orgSlug, resourceUrl, meta.get().sha256()));
    }

    private record ResolvedPage(String scopeName, String resourceUrl, String sha256) {
        Map<String, Object> toResponse(String pageKey, String scope) {
            return Map.of(
                "page_key", pageKey,
                "scope", scope,
                "store", scopeName,
                "resource_url", resourceUrl,
                "content_hash", sha256
            );
        }
    }
}
