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

// Public — no auth. Serves named assets at /resource/{store}/{directory}/{name}.
// Resolution: store name → store_id → directory_id → resource_id → bytes.
@RestController
public class ResourcePublicController {

    private final ResourceRepository resourceRepository;

    public ResourcePublicController(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    @GetMapping("/resource/{store}/{directory}/{name}")
    public ResponseEntity<?> serve(
            @PathVariable String store,
            @PathVariable String directory,
            @PathVariable String name,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {

        var storeId = resourceRepository.findStoreIdByName(store);
        if (storeId.isEmpty()) return notFound(store + "/" + directory + "/" + name);

        var dirId = resourceRepository.findDirectoryId(storeId.get(), directory);
        if (dirId.isEmpty()) return notFound(store + "/" + directory + "/" + name);

        var resourceId = resourceRepository.findAssetResourceId(storeId.get(), dirId.get(), name);
        if (resourceId.isEmpty()) return notFound(store + "/" + directory + "/" + name);

        // ETag check against metadata only — avoids blob read on cache hit.
        var meta = resourceRepository.findMetaById(resourceId.get());
        if (meta.isEmpty()) return notFound(store + "/" + directory + "/" + name);

        String etag = "\"" + meta.get().sha256() + "\"";
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .build();
        }

        var resource = resourceRepository.findById(resourceId.get());
        if (resource.isEmpty()) return notFound(store + "/" + directory + "/" + name);

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(resource.get().mimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
            .contentType(mediaType)
            .contentLength(resource.get().sizeBytes())
            .body(resource.get().data());
    }

    private ResponseEntity<?> notFound(String path) {
        return ResponseEntity.status(404).body(new ErrorResponse("Resource not found: " + path));
    }
}
