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
    private final SyncFeedRepository feeds;

    public SyncFeedController(SessionService sessionService, StoreRepository storeRepository,
                              ConfigService configService, SyncFeedRepository feeds) {
        this.sessionService = sessionService;
        this.storeRepository = storeRepository;
        this.configService = configService;
        this.feeds = feeds;
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
        int fetch = limit + 1; // one extra row tells us whether this is the last page

        List<SyncFeedRepository.Row> rows = switch (table) {
            case "products" -> feeds.products(orgId, since, afterId, fetch, storeRepository.getStoreConfig(storeId).vatRate());
            case "barcodes" -> feeds.barcodes(orgId, since, afterId, fetch);
            case "categories" -> feeds.categories(orgId, since, afterId, fetch);
            case "payment-methods" -> feeds.paymentMethods(since, afterId, fetch);
            case "store-payment-methods" -> feeds.storePaymentMethods(storeId, since, afterId, fetch);
            case "product-images" -> feeds.productImages(orgId, since, afterId, fetch);
            default -> null;
        };
        if (rows == null) {
            return ResponseEntity.status(404).body(new ErrorResponse("Unknown sync table: " + table));
        }
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
        int size = requested != null ? requested : configured(orgId);
        return Math.max(1, Math.min(size, MAX_SIZE));
    }

    private int configured(long orgId) {
        try {
            String v = configService.findAllProperties(orgId).get("sync_pull_patch_size");
            return v == null ? DEFAULT_SIZE : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SIZE;
        }
    }
}
