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

// Admin endpoints for the named resource library.
// All require EDIT_RESOURCES permission at the target store.
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
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);
        return ResponseEntity.ok(resourceRepository.listDirectories(storeId));
    }

    @PostMapping("/directories")
    public ResponseEntity<?> createDirectory(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @RequestBody Map<String, String> body) {
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        String name = body.get("name");
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required"));
        }
        if (!name.matches("[a-zA-Z0-9_\\-]+")) {
            return ResponseEntity.badRequest().body(
                new ErrorResponse("Directory name may only contain letters, digits, hyphens, and underscores"));
        }
        try {
            long id = resourceRepository.createDirectory(storeId, name.toLowerCase());
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
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        var dirId = resourceRepository.findDirectoryId(storeId, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);
        return ResponseEntity.ok(resourceRepository.listAssets(storeId, dirId.get()));
    }

    @PostMapping(value = "/directories/{dir}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String nameOverride) {
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        var dirId = resourceRepository.findDirectoryId(storeId, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);

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

        resourceRepository.upsertAsset(storeId, dirId.get(), assetName, resourceId);

        return ResponseEntity.ok(Map.of(
            "name", assetName,
            "url", "/resource/" + store + "/" + dir + "/" + assetName,
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
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        var fromDirId = resourceRepository.findDirectoryId(storeId, dir);
        if (fromDirId.isEmpty()) return dirNotFound(store, dir);

        String targetDirName = body.get("targetDir");
        if (targetDirName == null || targetDirName.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("targetDir is required"));

        var toDirId = resourceRepository.findDirectoryId(storeId, targetDirName);
        if (toDirId.isEmpty()) return dirNotFound(store, targetDirName);

        if (fromDirId.get().equals(toDirId.get()))
            return ResponseEntity.badRequest().body(new ErrorResponse("Source and target directory are the same"));

        boolean moved = resourceRepository.moveAsset(storeId, fromDirId.get(), toDirId.get(), name);
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
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        var dirId = resourceRepository.findDirectoryId(storeId, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);

        String newName = body.get("newName");
        if (newName == null || newName.isBlank())
            return ResponseEntity.badRequest().body(new ErrorResponse("newName is required"));

        boolean renamed = resourceRepository.renameAsset(storeId, dirId.get(), name, newName.trim());
        if (!renamed) return ResponseEntity.status(404).body(new ErrorResponse("Asset not found: " + name));
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/directories/{dir}/{name}")
    public ResponseEntity<?> deleteAsset(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String store,
            @PathVariable String dir,
            @PathVariable String name) {
        var storeId = resolveStore(store);
        if (storeId == null) return storeNotFound(store);
        sessionService.requirePermission(auth, Permission.EDIT_RESOURCES, storeId);

        var dirId = resourceRepository.findDirectoryId(storeId, dir);
        if (dirId.isEmpty()) return dirNotFound(store, dir);

        boolean deleted = resourceRepository.deleteAsset(storeId, dirId.get(), name);
        if (!deleted) return ResponseEntity.status(404).body(new ErrorResponse("Asset not found: " + name));
        return ResponseEntity.ok().build();
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Long resolveStore(String storeName) {
        return resourceRepository.findStoreIdByName(storeName).orElse(null);
    }

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
