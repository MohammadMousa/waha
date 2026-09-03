package com.waha.dashboard;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/charts")
public class ChartsController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ChartsRepository chartsRepository;
    private final SessionService sessionService;

    public ChartsController(ChartsRepository chartsRepository, SessionService sessionService) {
        this.chartsRepository = chartsRepository;
        this.sessionService = sessionService;
    }

    @GetMapping("/{type}")
    public ResponseEntity<?> chart(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable String type,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {

        sessionService.requirePermission(auth, Permission.VIEW_ALL_ORDERS, 1L);

        LocalDateTime start = from != null
                ? LocalDate.parse(from, DATE_FMT).atStartOfDay()
                : LocalDateTime.now().minusDays(30);
        LocalDateTime end = to != null
                ? LocalDate.parse(to, DATE_FMT).atTime(23, 59, 59)
                : LocalDateTime.now();

        List<Map<String, Object>> data = switch (type) {
            case "revenue-by-hours"      -> chartsRepository.revenueByHours(start, end);
            case "revenue-by-days"       -> chartsRepository.revenueByDays(start, end);
            case "revenue-by-months"     -> chartsRepository.revenueByMonths(start, end);
            case "orders-by-months"      -> chartsRepository.ordersByMonths(start, end);
            case "orders-by-days"        -> chartsRepository.ordersByDays(start, end);
            case "orders-by-hours"       -> chartsRepository.ordersByHours(start, end);
            case "revenue-by-products"   -> chartsRepository.revenueByProducts(start, end);
            case "revenue-by-categories" -> chartsRepository.revenueByCategories(start, end);
            case "revenue-by-branches"   -> chartsRepository.revenueByBranches(start, end);
            default -> null;
        };

        if (data == null) return ResponseEntity.badRequest().body("Unknown chart type: " + type);
        return ResponseEntity.ok(data);
    }
}
