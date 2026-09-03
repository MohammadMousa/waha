package com.waha.dashboard;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;

@RestController
@RequestMapping("/api/admin/dashboard")
public class DashboardController {

    private static final Set<String> VALID_PERIODS_SERIES  = Set.of("7d", "15d", "1m", "3m");
    private static final Set<String> VALID_PERIODS_MONTHLY = Set.of("6m", "1y", "2y");
    private static final Set<String> VALID_METRICS         = Set.of("revenue", "orders");

    private final DashboardRepository dashboardRepository;
    private final SessionService sessionService;

    public DashboardController(DashboardRepository dashboardRepository, SessionService sessionService) {
        this.dashboardRepository = dashboardRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/kpis")
    public ResponseEntity<?> kpis(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(dashboardRepository.getKpis());
    }

    @GetMapping("/series")
    public ResponseEntity<?> series(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "revenue") String metric,
            @RequestParam(defaultValue = "1m")      String period) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);

        if (!VALID_METRICS.contains(metric))
            return ResponseEntity.badRequest().body("metric must be one of: " + VALID_METRICS);
        if (!VALID_PERIODS_SERIES.contains(period))
            return ResponseEntity.badRequest().body("period must be one of: " + VALID_PERIODS_SERIES);

        return ResponseEntity.ok(dashboardRepository.getSeries(metric, period));
    }

    @GetMapping("/monthly")
    public ResponseEntity<?> monthly(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(defaultValue = "6m") String period) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);

        if (!VALID_PERIODS_MONTHLY.contains(period))
            return ResponseEntity.badRequest().body("period must be one of: " + VALID_PERIODS_MONTHLY);

        return ResponseEntity.ok(dashboardRepository.getMonthly(period));
    }

    @GetMapping("/recent-orders")
    public ResponseEntity<?> recentOrders(
            @RequestHeader(value = "Authorization", required = false) String auth) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);
        return ResponseEntity.ok(dashboardRepository.getRecentOrders());
    }
}
