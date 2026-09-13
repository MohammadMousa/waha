package com.waha.resource;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.common.ErrorResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

// Admin endpoints for the named resource library.
// {store} is either a real branch's store name (branch scope) or its organization's
// slug (org-level / global scope, store_id IS NULL). Never inferred by comparing the
// two — always resolved explicitly per-request via resolveScope().
// All require EDIT_RESOURCES permission at the target store (branch scope) or org
// (global scope).
@RestController
@RequestMapping("/api/resources/{store}")
public class ResourceAdminController {

    private final ResourceRepository resourceRepository;
    private final SessionService sessionService;

    public ResourceAdminController(ResourceRepository resourceRepository, SessionService sessionService) {
        this.resourceRepository = resourceRepository;
        this.sessionService = sessionService;
    }

    // ── Directories ──────────────────────────────────────────────────────────

    @GetMapping("/directories")
    public ResponseEntity<?> listDirectories(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        var directories = scope.isGlobal()
            ? resourceRepository.listDirectoriesGlobal(scope.orgId())
            : resourceRepository.listDirectories(scope.storeId());
        return ResponseEntity.ok(directories);
    }

    @PostMapping("/directories")
    public ResponseEntity<?> createDirectory(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @RequestBody Map<String, String> body) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        if (!name.matches("[a-zA-Z0-9_\\-]+")) {
            return ResponseEntity.badRequest().body(
                new ErrorResponse("Directory name may only contain letters, digits, hyphens, and underscores"));
        }
        try {
            long id = resourceRepository.createDirectory(scope.orgId(), scope.storeId(), name.toLowerCase());
            return ResponseEntity.ok(Map.of("id", id, "name", name.toLowerCase()));
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("Duplicate")) {
                return ResponseEntity.status(409).body(new ErrorResponse("Directory '" + name + "' already exists"));
            }
            throw e;
        }
    }

    // ── Assets ───────────────────────────────────────────────────────────────

    @GetMapping("/directories/{dir}")
    public ResponseEntity<?> listAssets(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        var dirId = findDirectoryId(scope, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);
        return ResponseEntity.ok(resourceRepository.listAssets(dirId.get()));
    }

    @PostMapping(value = "/directories/{dir}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String nameOverride) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        // Auto-create the directory on first save — e.g. a brand-new org has no
        // global "pages" directory yet until its first global page is saved.
        var dirId = findDirectoryId(scope, dir);
        if (dirId.isEmpty()) {
            if (!dir.matches("[a-zA-Z0-9_\\-]+")) return dirNotFound(store, dir);
            dirId = Optional.of(resourceRepository.createDirectory(scope.orgId(), scope.storeId(), dir));
        }

        if (file.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("file is required"));

        long maxBytes = resolveMaxBytes();
        if (file.getSize() > maxBytes) {
            return ResponseEntity.status(413).body(
                new ErrorResponse("File exceeds limit of " + (maxBytes / 1024 / 1024) + " MB"));
        }

        byte[] data;
        try { data = file.getBytes(); }
        catch (IOException e) { return ResponseEntity.status(500).body(new ErrorResponse("Failed to read file")); }

        String sha256 = sha256Hex(data);
        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String originalName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";
        String assetName = nameOverride != null && !nameOverride.isBlank() ? nameOverride.trim() : originalName;

        long resourceId = resourceRepository.findIdBySha256(sha256)
            .orElseGet(() -> resourceRepository.store(originalName, mimeType, data.length, sha256, data));

        resourceRepository.upsertAsset(dirId.get(), assetName, resourceId);

        String orgSlug = resourceRepository.findOrgSlugById(scope.orgId()).orElse(store);
        String publicUrl = scope.isGlobal()
            ? "/resource/" + orgSlug + "/" + dir + "/" + assetName
            : "/resource/" + orgSlug + "/" + store + "/" + dir + "/" + assetName;

        return ResponseEntity.ok(Map.of(
            "name", assetName,
            "url", publicUrl,
            "resourceId", resourceId,
            "sha256", sha256
        ));
    }

    @PatchMapping("/directories/{dir}/{name}/move")
    public ResponseEntity<?> moveAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        var fromDirId = findDirectoryId(scope, dir);
        if (fromDirId.isEmpty()) return dirNotFound(store, dir);

        String targetDirName = body.get("targetDir");
        if (targetDirName == null || targetDirName.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("targetDir is required"));

        var toDirId = findDirectoryId(scope, targetDirName);
        if (toDirId.isEmpty()) return dirNotFound(store, targetDirName);

        if (fromDirId.get().equals(toDirId.get()))
            return ResponseEntity.badRequest().body(new ErrorResponse("Source and target directory are the same"));

        boolean moved = resourceRepository.moveAsset(fromDirId.get(), toDirId.get(), name);
        if (!moved) return ResponseEntity.status(404).body(new ErrorResponse("Asset not found: " + name));
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/directories/{dir}/{name}/rename")
    public ResponseEntity<?> renameAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @PathVariable String name,
            @RequestBody Map<String, String> body) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        var dirId = findDirectoryId(scope, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);

        String newName = body.get("newName");
        if (newName == null || newName.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("newName is required"));

        boolean renamed = resourceRepository.renameAsset(dirId.get(), name, newName.trim());
        if (!renamed) return ResponseEntity.status(404).body(new ErrorResponse("Asset not found: " + name));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/directories/{dir}/{name}")
    public ResponseEntity<?> deleteAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @PathVariable String name) {
        var scope = resolveScope(store);
        if (scope == null) return storeNotFound(store);
        requireScopePermission(auth, scope);

        var dirId = findDirectoryId(scope, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);

        boolean deleted = resourceRepository.deleteAsset(dirId.get(), name);
        if (!deleted) return ResponseEntity.status(404).body(new ErrorResponse("Asset not found: " + name));
        return ResponseEntity.ok().build();
    }

    // ── Scope resolution ─────────────────────────────────────────────────────

    // storeId == null means org-level (global) scope.
    private record ResourceScope(long orgId, Long storeId) {
        boolean isGlobal() { return storeId == null; }
    }

    // {store} is resolved as a real branch's store name first (existing, org-scoped
    // via the store's own FK), then as an organization's slug (global scope). The two
    // namespaces aren't currently validated as disjoint at store-creation time — see
    // the doc note this PR leaves as a follow-up.
    private ResourceScope resolveScope(String segment) {
        var storeId = resourceRepository.findStoreIdByName(segment);
        if (storeId.isPresent()) {
            var orgId = resourceRepository.findOrgIdByStoreId(storeId.get());
            if (orgId.isEmpty()) return null;
            return new ResourceScope(orgId.get(), storeId.get());
        }
        var orgId = resourceRepository.findOrgIdBySlug(segment);
        return orgId.map(id -> new ResourceScope(id, null)).orElse(null);
    }

    private void requireScopePermission(String auth, ResourceScope scope) {
        if (scope.isGlobal()) {
            sessionService.requirePermissionForOrg(auth, Permission.EDIT_RESOURCES, scope.orgId());
        } else {
            sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, scope.storeId());
        }
    }

    private Optional<Long> findDirectoryId(ResourceScope scope, String dir) {
        return scope.isGlobal()
            ? resourceRepository.findDirectoryIdGlobal(scope.orgId(), dir)
            : resourceRepository.findDirectoryId(scope.storeId(), dir);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private long resolveMaxBytes() {
        // Falls back to 2 MB if the system property is missing or unparseable.
        try {
            return Long.parseLong(
                resourceRepository.getSystemProperty("resource.max_size_bytes")
                    .orElse("2097152"));
        } catch (NumberFormatException e) {
            return 2097152L;
        }
    }

    private ResponseEntity<?> storeNotFound(String store) {
        return ResponseEntity.status(404).body(new ErrorResponse("Store not found: " + store));
    }

    private ResponseEntity<?> dirNotFound(String store, String dir) {
        return ResponseEntity.status(404).body(new ErrorResponse("Directory not found: " + store + "/" + dir));
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
