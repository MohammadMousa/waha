package com.waha.store;

import com.waha.auth.Permission;
import com.waha.auth.SessionService;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/branch-groups")
public class BranchGroupController {

    private final NamedParameterJdbcTemplate jdbc;
    private final SessionService sessionService;

    public BranchGroupController(NamedParameterJdbcTemplate jdbc, SessionService sessionService) {
        this.jdbc = jdbc;
        this.sessionService = sessionService;
    }

    public record BranchGroupSummary(long id, String name, long organizationId) {}

    @GetMapping
    public List<BranchGroupSummary> list(
            @RequestHeader(value = "Authorization", required = false) String auth) {
        sessionService.requirePermission(auth, Permission.MANAGE_STORES, 1L);
        return jdbc.query(
            "SELECT id, name, organization_id FROM branch_groups ORDER BY id",
            Map.of(),
            (rs, i) -> new BranchGroupSummary(rs.getLong("id"), rs.getString("name"), rs.getLong("organization_id"))
        );
    }
}
