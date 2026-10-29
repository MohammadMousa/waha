package com.waha.store;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organizations")
public class OrganizationController {

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionService sessionService;

    public OrganizationController(NamedParameterJdbcTemplate jdbc, SessionService sessionService) {
        this.jdbc = jdbc;
        this.sessionService = sessionService;
    }

    public record OrgSummary(long id, String name, String slug) {}

    @GetMapping
    public org.springframework.http.ResponseEntity<List<OrgSummary>> list(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, 1L);
        // search (name or slug), page and size are optional; without them every organization is returned, as before.
        boolean hasSearch = search != null && !search.isBlank();
        String where = hasSearch ? " WHERE name LIKE :q OR slug LIKE :q" : "";
        Map<String, Object> params = new java.util.HashMap<>();
        if (hasSearch) params.put("q", "%" + search.trim() + "%");
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM organizations" + where, params, Long.class);
        String limit = "";
        if (page != null || size != null) {
            int s = Math.min(200, Math.max(1, size == null ? 50 : size));
            params.put("limit", s);
            params.put("offset", (long) Math.max(0, page == null ? 0 : page) * s);
            limit = " LIMIT :limit OFFSET :offset";
        }
        List<OrgSummary> items = jdbc.query(
            "SELECT id, name, slug FROM organizations" + where + " ORDER BY id" + limit,
            params,
            (rs, i) -> new OrgSummary(rs.getLong("id"), rs.getString("name"), rs.getString("slug"))
        );
        return org.springframework.http.ResponseEntity.ok().header("X-Total-Count", String.valueOf(total == null ? 0 : total)).body(items);
    }
}
