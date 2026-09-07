package com.waha.resource;

import com.waha.common.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

// Public — no auth. Two URL shapes:
//   /resource/{org}/{dir}/{name}           — org-level (global store)
//   /resource/{org}/{branch}/{dir}/{name}  — branch-level
// Resolution: org name → store_id (via org+branch or org's global store) → dir_id → resource.
@RestController
public class ResourcePublicController {

    private final ResourceRepository resourceRepository;

    public ResourcePublicController(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @GetMapping("/resource/{org}/{directory}/{name}")
    public ResponseEntity<?> serveOrgLevel(
            @PathVariable String org,
            @PathVariable String directory,
            @PathVariable String name,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        var orgId = resourceRepository.findOrgIdBySlug(org);
        if (orgId.isEmpty()) return notFound(org + "/" + directory + "/" + name);

        // Global store: store whose name matches the org name.
        var storeId = resourceRepository.findStoreIdByOrgAndName(orgId.get(), org);
        if (storeId.isEmpty()) return notFound(org + "/" + directory + "/" + name);

        return serve(storeId.get(), directory, name, org + "/" + directory + "/" + name, ifNoneMatch);
    }

    @GetMapping("/resource/{org}/{branch}/{directory}/{name}")
    public ResponseEntity<?> serveBranchLevel(
            @PathVariable String org,
            @PathVariable String branch,
            @PathVariable String directory,
            @PathVariable String name,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        var orgId = resourceRepository.findOrgIdBySlug(org);
        if (orgId.isEmpty()) return notFound(org + "/" + branch + "/" + directory + "/" + name);

        var storeId = resourceRepository.findStoreIdByOrgAndName(orgId.get(), branch);
        if (storeId.isEmpty()) return notFound(org + "/" + branch + "/" + directory + "/" + name);

        return serve(storeId.get(), directory, name, org + "/" + branch + "/" + directory + "/" + name, ifNoneMatch);
    }

    private ResponseEntity<?> serve(long storeId, String directory, String name, String displayPath, String ifNoneMatch) {
        var dirId = resourceRepository.findDirectoryId(storeId, directory);
        if (dirId.isEmpty()) return notFound(displayPath);

        var resourceId = resourceRepository.findAssetResourceId(dirId.get(), name);
        if (resourceId.isEmpty()) return notFound(displayPath);

        var meta = resourceRepository.findMetaById(resourceId.get());
        if (meta.isEmpty()) return notFound(displayPath);

        String etag = "\"" + meta.get().sha256() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .build();
        }

        var resource = resourceRepository.findById(resourceId.get());
        if (resource.isEmpty()) return notFound(displayPath);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(resource.get().mimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .header(HttpHeaders.CONTENT_DISPOSITION, ResourceController.contentDisposition(name))
            .contentType(mediaType)
            .contentLength(resource.get().sizeBytes())
            .body(resource.get().data());
    }

    private ResponseEntity<?> notFound(String path) {
        return ResponseEntity.status(404).body(new ErrorResponse("Resource not found: " + path));
    }
}
