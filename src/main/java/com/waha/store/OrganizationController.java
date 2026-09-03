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

    public record OrgSummary(long id, String name, String type, Long parentId) {}

    @GetMapping
    public List<OrgSummary> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, 1L);
        return jdbc.query(
            "SELECT id, name, type, parent_id FROM organizations ORDER BY id",
            Map.of(),
            (rs, i) -> {
                long pid = rs.getLong("parent_id");
                Long parentId = rs.wasNull() ? null : pid;
                return new OrgSummary(rs.getLong("id"), rs.getString("name"),
                    rs.getString("type"), parentId);
            }
        );
    }
}
