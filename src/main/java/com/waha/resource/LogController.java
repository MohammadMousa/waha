package com.waha.resource;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
public class LogController {

    private final ResourceRepository resourceRepository;
    private final SessionService sessionService;

    @org.springframework.beans.factory.annotation.Value("${waha.logs.retention-days:30}")
    private int retentionDays;

    public LogController(ResourceRepository resourceRepository, SessionService sessionService) {
        this.resourceRepository = resourceRepository;
        this.sessionService = sessionService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "0")   int page,
            @RequestParam(defaultValue = "20")  int size,
            @RequestParam(required = false)     Long deviceId,
            @RequestParam(required = false)     String from,
            @RequestParam(required = false)     String to) {
        try {
            UserSession session = requireManageDevices(auth);
            int safeSize = Math.min(size, 100);
            Instant fromInstant = from != null ? Instant.parse(from) : null;
            Instant toInstant   = to   != null ? Instant.parse(to)   : null;

            List<ResourceRepository.LogEntry> entries = resourceRepository.listLogs(
                session.organizationId(), deviceId, fromInstant, toInstant, page * safeSize, safeSize);
            ResourceRepository.LogCount counts = resourceRepository.countLogs(
                session.organizationId(), deviceId, fromInstant, toInstant);

            List<Map<String, Object>> items = new ArrayList<>();
            for (var e : entries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id",          e.id());
                m.put("createdAt",   e.createdAt().toString());
                m.put("deviceId",    e.deviceId());
                m.put("deviceName",  e.deviceName());
                m.put("storeId",     e.storeId());
                m.put("storeName",   e.storeName());
                m.put("sizeBytes",   e.sizeBytes());
                items.add(m);
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("items",          items);
            resp.put("totalCount",     counts.totalCount());
            resp.put("totalSizeBytes", counts.totalSizeBytes());
            resp.put("retentionDays",  retentionDays);
            return ResponseEntity.ok(resp);
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(new ErrorResponse("Invalid parameters: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> read(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        try {
            UserSession session = requireManageDevices(auth);
            var resource = resourceRepository.findById(id);
            if (resource.isEmpty()) {
                return ResponseEntity.status(404).body(new ErrorResponse("Log " + id + " not found"));
            }
            // Verify belongs to caller's org via device ownership check.
            var meta = resourceRepository.findMetaById2(id);
            if (meta.isEmpty() || meta.get().deviceId() == null) {
                return ResponseEntity.status(404).body(new ErrorResponse("Log " + id + " not found"));
            }
            if (!resourceRepository.isLogOwnedByOrg(id, session.organizationId())) {
                return ResponseEntity.status(403).body(new ErrorResponse("Access denied"));
            }
            return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .contentLength(resource.get().sizeBytes())
                .header("Content-Disposition", "inline; filename=\"" + resource.get().filename() + "\"")
                .body(new String(resource.get().data(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable long id) {
        try {
            UserSession session = requireManageDevices(auth);
            boolean deleted = resourceRepository.deleteLog(id, session.organizationId());
            if (!deleted) {
                return ResponseEntity.status(404).body(new ErrorResponse("Log " + id + " not found or not accessible"));
            }
            return ResponseEntity.noContent().build();
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/delete")
    public ResponseEntity<?> deleteBatch(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        try {
            UserSession session = requireManageDevices(auth);
            @SuppressWarnings("unchecked")
            List<Number> rawIds = (List<Number>) body.get("ids");
            if (rawIds == null || rawIds.isEmpty()) {
                return ResponseEntity.badRequest().body(new ErrorResponse("ids is required"));
            }
            List<Long> ids = rawIds.stream().map(Number::longValue).toList();
            int deleted = resourceRepository.deleteLogs(ids, session.organizationId());
            return ResponseEntity.ok(Map.of("deleted", deleted));
        } catch (UnauthorizedException e) {
            return ResponseEntity.status(401).body(new ErrorResponse(e.getMessage()));
        } catch (ForbiddenException e) {
            return ResponseEntity.status(403).body(new ErrorResponse(e.getMessage()));
        }
    }

    private UserSession requireManageDevices(String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        sessionService.requirePermission(authHeader, Permission.MANAGE_DEVICES, session.storeId());
        return session;
    }
}
