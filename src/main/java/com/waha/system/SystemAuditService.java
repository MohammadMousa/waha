package com.waha.system;

import com.waha.audit.AuditService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

// Super-admin audit: critical system actions only (setting changes, cache actions, lockouts cleared, audit deletes).
// Stored in the shared audit history with scope SYSTEM. Writing never fails the action it records.
@Service
public class SystemAuditService {

    private final AuditService audit;

    public SystemAuditService(AuditService audit) {
        this.audit = audit;
    }

    public void record(long actorUserId, String actorName, String action, String target,
                       String oldValue, String newValue, String details, String ip) {
        try {
            audit.write(AuditService.SYSTEM, 0, "SYSTEM", null, actorUserId, actorName, action, target,
                oldValue, newValue, details, ip);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(SystemAuditService.class)
                .warn("System audit write failed ({} {}): {}", action, target, e.getMessage());
        }
    }

    // Newest first. Filters are optional; from / to are dates (inclusive, UTC).
    public Map<String, Object> list(int page, int size, String action, String actor, LocalDate from, LocalDate to) {
        return audit.list(new AuditService.Query(AuditService.SYSTEM, null, null, null, action, actor, from, to, page, size));
    }

    public int delete(long id) {
        return audit.delete(List.of(id), AuditService.SYSTEM);
    }

    // before == null clears every system row; otherwise rows created before that date (UTC).
    public int clear(LocalDate before) {
        return audit.clear(before, AuditService.SYSTEM);
    }
}
