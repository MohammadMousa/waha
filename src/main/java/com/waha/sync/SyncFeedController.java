package com.waha.sync;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ErrorResponse;
import com.waha.config.ConfigService;
import com.waha.store.StoreRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Offline pull feeds: GET /api/sync/{table}?since=&afterId=&size= (see docs/offline.md).
// Needs a session with a store; a missing or revoked session is a clear 401, so the kiosk can tell
// "signed out" apart from "offline".
@RestController
@RequestMapping("/api/sync")
public class SyncFeedController {

    static final int DEFAULT_SIZE = 200;
    static final int MAX_SIZE = 500;

    private final SessionService sessionService;
    private final StoreRepository storeRepository;
    private final ConfigService configService;
    private final SyncFeedReader reader;
    private final SyncRemovalRepository removals;
    private final SyncTableConfigService tableConfig;

    public SyncFeedController(SessionService sessionService, StoreRepository storeRepository,
                              ConfigService configService, SyncFeedReader reader,
                              SyncRemovalRepository removals, SyncTableConfigService tableConfig) {
        this.sessionService = sessionService;
        this.storeRepository = storeRepository;
        this.configService = configService;
        this.reader = reader;
        this.removals = removals;
        this.tableConfig = tableConfig;
    }

    // Rows deleted on the server. Without afterId: starting position only (nothing listed). With afterId: the removals
    // after it. resetRequired = the list was cleaned past the device's position, so it must pull the tables in full.
    @GetMapping("/removals")
    public ResponseEntity<?> removals(@RequestParam(required = false) Long afterId,
                                      @RequestParam(required = false) Integer size,
                                      @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.storeId() == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("No store selected for this session"));
        }
        long orgId = storeRepository.findCompanyId(session.storeId());
        int limit = pageSize(size, orgId);
        Map<String, Object> body = new LinkedHashMap<>();
        long latest = removals.latestId(orgId);
        // Switched off by the organization: records are still kept, only the listing stops (docs/sync-combined-pull.md, section 4).
        // The starting position (no afterId) is still handed out; with an afterId the page is empty and the position stays.
        if (afterId != null && !tableConfig.get(orgId, SyncTable.REMOVALS).active()) {
            body.put("items", List.of());
            body.put("last", true);
            body.put("next", Map.of("afterId", afterId));
            body.put("resetRequired", false);
            body.put("serverTime", Instant.now());
            return ResponseEntity.ok(body);
        }
        boolean reset = afterId != null && afterId < removals.purgedUpTo();
        List<Map<String, Object>> items = List.of();
        boolean last = true;
        long next = latest;
        if (afterId != null && !reset) {
            var rows = removals.after(orgId, afterId, limit + 1);
            last = rows.size() <= limit;
            var page = last ? rows : rows.subList(0, limit);
            items = page.stream().map(SyncRemovalRepository.Removal::fields).toList();
            next = page.isEmpty() ? afterId : page.get(page.size() - 1).id();
        }
        body.put("items", items);
        body.put("last", last);
        body.put("next", Map.of("afterId", next));
        body.put("resetRequired", reset);
        body.put("serverTime", Instant.now());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{table}")
    public ResponseEntity<?> feed(@PathVariable String table,
                                  @RequestParam(required = false) Instant since,
                                  @RequestParam(required = false, defaultValue = "0") long afterId,
                                  @RequestParam(required = false) Integer size,
                                  @RequestHeader(value = "Authorization", required = false) String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.storeId() == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("No store selected for this session"));
        }
        long storeId = session.storeId();
        long orgId = storeRepository.findCompanyId(storeId);
        int limit = pageSize(size, orgId);
        SyncTable syncTable = SyncTable.feedByKey(table).orElse(null);
        if (syncTable == null) {
            return ResponseEntity.status(404).body(new ErrorResponse("Unknown sync table: " + table));
        }
        // Switched off by the organization: an empty last page and the cursor unchanged, so the device pulls nothing.
        if (!tableConfig.get(orgId, syncTable).active()) {
            return ResponseEntity.ok(page(List.of(), limit, since, afterId));
        }
        int fetch = limit + 1; // one extra row tells us whether this is the last page
        List<SyncFeedRepository.Row> rows = reader.forStore(orgId, storeId).fetch(syncTable, since, afterId, fetch);
        return ResponseEntity.ok(page(rows, limit, since, afterId));
    }

    // Builds the response: items, last-page flag and the cursor to send next. With no rows the cursor stays where it was.
    static Map<String, Object> page(List<SyncFeedRepository.Row> rows, int limit, Instant since, long afterId) {
        boolean last = rows.size() <= limit;
        List<SyncFeedRepository.Row> items = last ? rows : rows.subList(0, limit);
        Map<String, Object> next = new LinkedHashMap<>();
        if (items.isEmpty()) {
            next.put("since", since);
            next.put("afterId", afterId);
        } else {
            var lastRow = items.get(items.size() - 1);
            next.put("since", lastRow.updatedAt());
            next.put("afterId", lastRow.id());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items.stream().map(SyncFeedRepository.Row::fields).toList());
        body.put("last", last);
        body.put("next", next);
        body.put("serverTime", Instant.now());
        return body;
    }

    private int pageSize(Integer requested, long orgId) {
        int size = requested != null ? requested : configured(configService, orgId);
        return Math.max(1, Math.min(size, MAX_SIZE));
    }

    // The organization's rows per page (sync_pull_patch_size), or the default.
    static int configured(ConfigService configService, long orgId) {
        try {
            String v = configService.findAllProperties(orgId).get("sync_pull_patch_size");
            return v == null ? DEFAULT_SIZE : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }
}
