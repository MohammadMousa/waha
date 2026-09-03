package com.waha.resource;

import com.waha.common.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/resources")
public class ResourceController {

    private static final long MAX_SIZE_BYTES = 10 * 1024 * 1024; // 10 MB

    private final ResourceRepository resourceRepository;

    public ResourceController(ResourceRepository resourceRepository) {
        this.resourceRepository = resourceRepository;
    }

    // Upload a file. Returns {id, sha256} regardless of whether this was a
    // fresh store or a deduplication hit (same bytes already existed).
    // The caller uses id to reference the resource from other tables.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.status(400).body(new ErrorResponse("file is required"));
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            return ResponseEntity.status(413).body(new ErrorResponse("File exceeds 10 MB limit"));
        }

        String mimeType = file.getContentType() != null ? file.getContentType() : "application/octet-stream";
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload";

        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.status(500).body(new ErrorResponse("Failed to read uploaded file"));
        }

        String sha256 = sha256Hex(data);

        // Deduplication: same content already stored — return existing id.
        long id = resourceRepository.findIdBySha256(sha256)
            .orElseGet(() -> resourceRepository.store(filename, mimeType, data.length, sha256, data));

        return ResponseEntity.ok(Map.of("id", id, "sha256", sha256));
    }

    // Serve a resource. Resources are content-addressed (SHA-256 dedup) so
    // the same id always returns the same bytes — safe to cache forever.
    // ETag = sha256. Clients send If-None-Match on repeat requests; if the
    // etag matches we return 304 with no body, saving the round-trip.
    @GetMapping("/{id}")
    public ResponseEntity<?> serve(@PathVariable long id,
                                    @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        var meta = resourceRepository.findMetaById(id);
        if (meta.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Resource " + id + " not found"));
        }

        String etag = "\"" + meta.get().sha256() + "\"";

        // Client already has this exact content — skip the blob fetch entirely.
        if (etag.equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                .header(HttpHeaders.ETAG, etag)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .build();
        }

        var resource = resourceRepository.findById(id);
        if (resource.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Resource " + id + " not found"));
        }

        MediaType mediaType;
        try {
            mediaType = MediaType.parseMediaType(resource.get().mimeType());
        } catch (Exception e) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM;
        }

        return ResponseEntity.ok()
            .header(HttpHeaders.ETAG, etag)
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
            .header(HttpHeaders.CONTENT_DISPOSITION, contentDisposition(resource.get().filename()))
            .contentType(mediaType)
            .contentLength(resource.get().sizeBytes())
            .body(resource.get().data());
    }

    static String contentDisposition(String filename) {
        try {
            String encoded = java.net.URLEncoder.encode(filename, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
            return "inline; filename=\"" + filename.replaceAll("[^\\x20-\\x7E]", "_") + "\"; filename*=UTF-8''" + encoded;
        } catch (Exception e) {
            return "inline; filename=\"file\"";
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
