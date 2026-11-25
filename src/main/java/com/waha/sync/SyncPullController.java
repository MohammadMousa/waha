package com.waha.sync;

import com.fasterxml.jackson.databind.JsonNode;
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

// POST /api/sync/pull: device settings, removals and every requested table in one answer (docs/sync-combined-pull.md).
// It only reads, so a device may repeat it. Same sign-in rules as the single feeds.
@RestController
@RequestMapping("/api/sync")
public class SyncPullController {

    private final SessionService sessionService;
    private final StoreRepository storeRepository;
    private final ConfigService configService;
    private final SyncFeedReader reader;
    private final SyncRemovalRepository removals;
    private final SyncTableConfigService tableConfig;
    private final SyncDeviceController deviceController;

    public SyncPullController(SessionService sessionService, StoreRepository storeRepository, ConfigService configService,
                              SyncFeedReader reader, SyncRemovalRepository removals, SyncTableConfigService tableConfig,
                              SyncDeviceController deviceController) {
        this.sessionService = sessionService;
        this.storeRepository = storeRepository;
        this.configService = configService;
        this.reader = reader;
        this.removals = removals;
        this.tableConfig = tableConfig;
        this.deviceController = deviceController;
    }

    @PostMapping("/pull")
    public ResponseEntity<?> pull(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                  @RequestBody(required = false) JsonNode body) {
        UserSession session = sessionService.requireSession(authHeader);
        if (session.storeId() == null) {
            return ResponseEntity.status(400).body(new ErrorResponse("No store selected for this session"));
        }
        SyncPullService.Request request;
        try {
            request = parse(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
        long storeId = session.storeId();
        long orgId = storeRepository.findCompanyId(storeId);
        Instant now = Instant.now();

        SyncPullService.Removals source = new SyncPullService.Removals() {
            @Override public long latestId() { return removals.latestId(orgId); }
            @Override public long purgedUpTo() { return removals.purgedUpTo(); }
            @Override public List<SyncRemovalRepository.Removal> after(long afterId, int limit) { return removals.after(orgId, afterId, limit); }
        };
        Map<String, Object> result = SyncPullService.pull(request, SyncFeedController.configured(configService, orgId),
            tableConfig.forOrganization(orgId), now, reader.forStore(orgId, storeId), source);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("serverTime", now);
        if (session.deviceId() != null) {
            Map<String, Object> device = deviceController.deviceConfigBody(session);
            if (device != null) out.put("deviceConfig", device);
        }
        out.putAll(result);
        return ResponseEntity.ok(out);
    }

    // Bad input is a plain 400 message.
    static SyncPullService.Request parse(JsonNode body) {
        if (body == null || body.isNull()) body = new com.fasterxml.jackson.databind.node.ObjectNode(com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        if (!body.isObject()) throw new IllegalArgumentException("The body must be a JSON object");
        Integer size = null;
        JsonNode sz = body.get("size");
        if (sz != null && !sz.isNull()) {
            if (!sz.canConvertToInt() || !sz.isIntegralNumber() || sz.asInt() < 1) throw new IllegalArgumentException("size must be a whole number of 1 or more");
            size = sz.asInt();
        }
        boolean force = false;
        JsonNode f = body.get("force");
        if (f != null && !f.isNull()) {
            if (!f.isBoolean()) throw new IllegalArgumentException("force must be true or false");
            force = f.asBoolean();
        }
        Long removalsAfterId = null;
        JsonNode ra = body.get("removalsAfterId");
        if (ra != null && !ra.isNull()) {
            if (!ra.isIntegralNumber() || ra.asLong() < 0) throw new IllegalArgumentException("removalsAfterId must be a whole number of 0 or more");
            removalsAfterId = ra.asLong();
        }
        Instant removalsCheckedAt = instant(body, "removalsCheckedAt");
        Map<String, SyncPullService.Cursor> tables = new LinkedHashMap<>();
        JsonNode t = body.get("tables");
        if (t != null && !t.isNull()) {
            if (!t.isObject()) throw new IllegalArgumentException("tables must be an object");
            var it = t.fields();
            while (it.hasNext()) {
                var e = it.next();
                JsonNode c = e.getValue();
                if (c == null || c.isNull() || (c.isObject() && c.isEmpty())) {
                    tables.put(e.getKey(), null);
                    continue;
                }
                if (!c.isObject()) throw new IllegalArgumentException("tables." + e.getKey() + " must be an object or null");
                long afterId = 0;
                JsonNode a = c.get("afterId");
                if (a != null && !a.isNull()) {
                    if (!a.isIntegralNumber() || a.asLong() < 0) throw new IllegalArgumentException("afterId of " + e.getKey() + " must be a whole number of 0 or more");
                    afterId = a.asLong();
                }
                tables.put(e.getKey(), new SyncPullService.Cursor(instant(c, "since"), afterId, instant(c, "checkedAt")));
            }
        }
        return new SyncPullService.Request(size, force, removalsAfterId, removalsCheckedAt, tables);
    }

    private static Instant instant(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || n.isNull()) return null;
        try {
            return Instant.parse(n.asText());
        } catch (Exception e) {
            throw new IllegalArgumentException(field + " must be an ISO date-time like 2026-11-06T10:15:30Z");
        }
    }
}
