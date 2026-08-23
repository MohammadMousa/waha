package com.waha.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
public class CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    // Returns categories visible to a store. Scope resolution is simple here
    // (categories aren't ranked by specificity per barcode the way products
    // are) - a category is visible to a store if it is global (scope_store_id
    // IS NULL) or belongs to one of the stores in the requesting store's
    // ancestor chain (including itself). The customer-facing browse endpoint
    // only returns public=TRUE categories; an admin endpoint can drop that
    // filter later without changing this query.
    public List<Category> findForStore(List<Long> scopeChain, boolean publicOnly) {
        String publicFilter = publicOnly ? " AND c.public = TRUE" : "";
        return jdbc.query(
            "SELECT id, scope_store_id, name, public, active, sort_order " +
            "FROM categories c " +
            "WHERE c.active = TRUE " +
            "  AND (c.scope_store_id IS NULL OR c.scope_store_id IN (:scopeIds))" +
            publicFilter +
            " ORDER BY c.sort_order, c.id",
            Map.of("scopeIds", scopeChain),
            (rs, i) -> {
                long scopeId = rs.getLong("scope_store_id");
                Long scopeStoreId = rs.wasNull() ? null : scopeId;
                return new Category(
                    rs.getLong("id"),
                    scopeStoreId,
                    parseJsonOrNull(rs.getString("name")),
                    rs.getBoolean("public"),
                    rs.getBoolean("active"),
                    rs.getInt("sort_order")
                );
            }
        );
    }

    private JsonNode parseJsonOrNull(String raw) {
        if (raw == null) return null;
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }
}
