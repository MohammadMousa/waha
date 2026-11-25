package com.waha.category;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class CategoryRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CategoryRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Category> findForStore(long companyId, boolean publicOnly) {
        String publicFilter = publicOnly ? " AND c.public = TRUE" : "";
        return jdbc.query(
            "SELECT id, company_id, name, public, active, sort_order, image_resource_id, `key`, updated_at " +
            "FROM categories c " +
            "WHERE c.active = TRUE AND c.company_id = :companyId" +
            publicFilter +
            " ORDER BY c.sort_order, c.id",
            Map.of("companyId", companyId),
            (rs, i) -> mapCategory(rs)
        );
    }

    public Optional<Category> findById(long id) {
        List<Category> results = jdbc.query(
            "SELECT id, company_id, name, public, active, sort_order, image_resource_id, `key`, updated_at " +
            "FROM categories WHERE id = :id",
            Map.of("id", id), (rs, i) -> mapCategory(rs)
        );
        return results.stream().findFirst();
    }

    public void patch(long id, com.fasterxml.jackson.databind.JsonNode body) {
        List<String> setClauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();

        if (body.has("name")) {
            setClauses.add("name = ?");
            params.add(body.get("name").toString());
        }
        if (body.has("imageResourceId")) {
            setClauses.add("image_resource_id = ?");
            com.fasterxml.jackson.databind.JsonNode img = body.get("imageResourceId");
            params.add(img.isNull() ? null : img.longValue());
        }

        if (setClauses.isEmpty()) return;
        params.add(id);
        jdbc.getJdbcTemplate().update(
            "UPDATE categories SET " + String.join(", ", setClauses) + " WHERE id = ?",
            params.toArray()
        );
    }

    public boolean resourceExists(long id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM resources WHERE id = :id", Map.of("id", id), Long.class);
        return n != null && n > 0;
    }

    // New category at the end of the organization's list. Returns the new id.
    public long create(long organizationId, String nameJson, Long imageResourceId) {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(sort_order), 0) FROM categories WHERE company_id = :org",
            Map.of("org", organizationId), Long.class);
        var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update("INSERT INTO categories (name, company_id, sort_order, image_resource_id) VALUES (:name, :org, :sort, :img)",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("name", nameJson).addValue("org", organizationId)
                .addValue("sort", (max == null ? 0 : max) + 1).addValue("img", imageResourceId), keys);
        return keys.getKey().longValue();
    }

    public long productCount(long id) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE category_id = :id", Map.of("id", id), Long.class);
        return n == null ? 0 : n;
    }

    // Really deletes an empty category and records the removal for devices.
    @Transactional
    public void delete(long id) {
        jdbc.getJdbcTemplate().update(
            "INSERT INTO sync_removals (organization_id, table_name, row_id) SELECT company_id, 'categories', id FROM categories WHERE id = ?", id);
        jdbc.getJdbcTemplate().update("DELETE FROM categories WHERE id = ?", id);
    }

    private Category mapCategory(java.sql.ResultSet rs) throws java.sql.SQLException {
        long imgId = rs.getLong("image_resource_id");
        Long imageResourceId = rs.wasNull() ? null : imgId;
        return new Category(
            rs.getLong("id"),
            rs.getLong("company_id"),
            parseJsonOrNull(rs.getString("name")),
            rs.getBoolean("public"),
            rs.getBoolean("active"),
            rs.getInt("sort_order"),
            imageResourceId,
            rs.getString("key"),
            rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toInstant()
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
