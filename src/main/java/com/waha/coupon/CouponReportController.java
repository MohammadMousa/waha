package com.waha.coupon;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

// Admin dashboard: coupon sales report (paid orders that used a coupon) and its export.
@RestController
@RequestMapping("/api/admin/coupons/report")
public class CouponReportController {

    private final CouponReportService report;
    private final SessionService sessionService;

    public CouponReportController(CouponReportService report, SessionService sessionService) {
        this.report = report;
        this.sessionService = sessionService;
    }

    private long org(String auth) {
        UserSession session = sessionService.requireSession(auth);
        sessionService.requirePermissionForOrg(session, Permission.VIEW_COUPONS, session.organizationId());
        return session.organizationId();
    }

    private static ResponseEntity<?> orgRequired() {
        return ResponseEntity.status(409).body(Map.of("error", "ORGANIZATION_REQUIRED", "message", "Pick an organization first"));
    }

    private static ResponseEntity<?> invalid(CouponReportService.InvalidFilter e) {
        return ResponseEntity.badRequest().body(Map.of("message", "Some filters are not valid", "errors", e.errors));
    }

    @GetMapping
    public ResponseEntity<?> get(@RequestHeader(value = "Authorization", required = false) String auth,
                                 @RequestParam(defaultValue = "orders") String groupBy,
                                 @RequestParam(required = false) String from,
                                 @RequestParam(required = false) String to,
                                 @RequestParam(required = false) Long storeId,
                                 @RequestParam(required = false) Long couponId,
                                 @RequestParam(required = false) Long employeeId,
                                 @RequestParam(required = false) Long offerId,
                                 @RequestParam(required = false) String username,
                                 @RequestParam(required = false) String sort,
                                 @RequestParam(required = false) String dir,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "20") int size) {
        long org = org(auth);
        if (org <= 0) return orgRequired();
        size = Math.min(Math.max(size, 1), 200);
        page = Math.max(page, 0);
        try {
            var f = new CouponReportService.Filter(groupBy, from, to, storeId, couponId, employeeId, offerId, username, sort, dir);
            var r = report.report(org, f, page, size);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("groupBy", groupBy.toLowerCase());
            out.put("summary", r.summary());
            out.put("items", r.items());
            out.put("total", r.total());
            out.put("page", page);
            out.put("size", size);
            return ResponseEntity.ok(out);
        } catch (CouponReportService.InvalidFilter e) {
            return invalid(e);
        }
    }

    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value = "Authorization", required = false) String auth,
                                    @RequestParam(defaultValue = "csv") String format,
                                    @RequestParam(defaultValue = "orders") String groupBy,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to,
                                    @RequestParam(required = false) Long storeId,
                                    @RequestParam(required = false) Long couponId,
                                    @RequestParam(required = false) Long employeeId,
                                    @RequestParam(required = false) Long offerId,
                                    @RequestParam(required = false) String username,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) String dir) {
        long org = org(auth);
        if (org <= 0) return orgRequired();
        String fmt = format.toLowerCase();
        if (!fmt.equals("csv") && !fmt.equals("xlsx"))
            return ResponseEntity.badRequest().body(Map.of("message", "Some filters are not valid", "errors", Map.of("format", "format must be csv or xlsx")));
        try {
            var f = new CouponReportService.Filter(groupBy, from, to, storeId, couponId, employeeId, offerId, username, sort, dir);
            byte[] file = report.export(org, f, fmt);
            MediaType type = fmt.equals("xlsx")
                ? MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                : MediaType.parseMediaType("text/csv;charset=UTF-8");
            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"coupon-sales-" + groupBy.toLowerCase() + "." + fmt + "\"")
                .contentType(type).contentLength(file.length).body(file);
        } catch (CouponReportService.InvalidFilter e) {
            return invalid(e);
        }
    }
}
