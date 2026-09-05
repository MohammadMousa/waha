package com.waha.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.common.InvalidRequestException;
import com.waha.store.dto.StoreSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

@Repository
public class StoreRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StoreRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public StoreConfig getStoreConfig(long storeId) {
        List<StoreConfig> results = jdbcTemplate.query(
            "SELECT id, currency, vat_rate FROM stores WHERE id = ?",
            (rs, i) -> new StoreConfig(rs.getLong("id"), rs.getString("currency"), rs.getBigDecimal("vat_rate")),
            storeId
        );
        if (results.isEmpty()) {
            throw new InvalidRequestException("Store not found: " + storeId);
        }
        return results.get(0);
    }

    public Optional<Long> findDefaultStoreId() {
        List<Long> results = jdbcTemplate.query(
            "SELECT CAST(value AS UNSIGNED) FROM system_properties WHERE `key` = 'default_store_id'",
            (rs, i) -> rs.getLong(1)
        );
        return results.stream().findFirst();
    }

    public java.util.Map<String, String> findAllProperties() {
        java.util.Map<String, String> props = new java.util.LinkedHashMap<>();
        jdbcTemplate.query("SELECT `key`, value FROM system_properties",
            (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                props.put(rs.getString("key"), rs.getString("value")));
        return props;
    }

    public List<StoreSummary> findPublicStores() {
        return jdbcTemplate.query(
            "SELECT id, name, display_name, currency, image_resource_id FROM stores WHERE public = TRUE AND active = TRUE ORDER BY name",
            (rs, i) -> {
                String rawJson = rs.getString("display_name");
                JsonNode displayName = parseJsonOrNull(rawJson);
                long imgId = rs.getLong("image_resource_id");
                Long imageResourceId = rs.wasNull() ? null : imgId;
                return new StoreSummary(rs.getLong("id"), rs.getString("name"), displayName, rs.getString("currency"), imageResourceId);
            }
        );
    }

    private JsonNode parseJsonOrNull(String rawJson) {
        if (rawJson == null) return null;
        try {
            return objectMapper.readTree(rawJson);
        } catch (Exception e) {
            return null;
        }
    }

    public List<StoreSummary> findAdminStores(long rootStoreId) {
        return jdbcTemplate.query(
            "SELECT id, name, display_name, currency, image_resource_id FROM stores" +
            " WHERE active = TRUE AND (? = 1 OR id = ?) ORDER BY id",
            (rs, i) -> {
                String rawJson = rs.getString("display_name");
                JsonNode displayName = parseJsonOrNull(rawJson);
                long imgId = rs.getLong("image_resource_id");
                Long imageResourceId = rs.wasNull() ? null : imgId;
                return new StoreSummary(rs.getLong("id"), rs.getString("name"), displayName, rs.getString("currency"), imageResourceId);
            },
            rootStoreId, rootStoreId
        );
    }

    public Optional<Long> findAdminRootStore(long userId) {
        List<Long> results = jdbcTemplate.query(
            "SELECT ur.scope_id FROM user_roles ur" +
            " JOIN roles r ON ur.role_id = r.id" +
            " JOIN stores s ON ur.scope_id = s.id" +
            " WHERE ur.user_id = ? AND r.name IN ('ADMIN', 'SUPER_ADMIN') AND s.active = TRUE" +
            " ORDER BY ur.scope_id ASC LIMIT 1",
            (rs, i) -> rs.getLong(1),
            userId
        );
        return results.stream().findFirst();
    }

    public boolean isSelectable(long storeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = ? AND public = TRUE AND active = TRUE",
            Integer.class, storeId
        );
        return count != null && count > 0;
    }

    public boolean isAdminSelectable(long storeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = ? AND active = TRUE",
            Integer.class, storeId
        );
        return count != null && count > 0;
    }

    public Optional<StoreSummary> findById(long storeId) {
        List<StoreSummary> results = jdbcTemplate.query(
            "SELECT id, name, display_name, currency, image_resource_id FROM stores WHERE id = ?",
            (rs, i) -> {
                JsonNode displayName = parseJsonOrNull(rs.getString("display_name"));
                long imgId = rs.getLong("image_resource_id");
                Long imageResourceId = rs.wasNull() ? null : imgId;
                return new StoreSummary(rs.getLong("id"), rs.getString("name"), displayName,
                    rs.getString("currency"), imageResourceId);
            },
            storeId
        );
        return results.stream().findFirst();
    }

    public record StoreAdminDetail(long id, String name, com.fasterxml.jackson.databind.JsonNode displayName,
            String currency, Long imageResourceId, boolean active, boolean publicFlag) {}

    public Optional<StoreAdminDetail> findByIdAdmin(long storeId) {
        List<StoreAdminDetail> results = jdbcTemplate.query(
            "SELECT id, name, display_name, currency, image_resource_id, active, `public` FROM stores WHERE id = ?",
            (rs, i) -> {
                com.fasterxml.jackson.databind.JsonNode dn = parseJsonOrNull(rs.getString("display_name"));
                long imgId = rs.getLong("image_resource_id");
                Long imageResourceId = rs.wasNull() ? null : imgId;
                return new StoreAdminDetail(rs.getLong("id"), rs.getString("name"), dn,
                    rs.getString("currency"), imageResourceId, rs.getBoolean("active"), rs.getBoolean("public"));
            },
            storeId
        );
        return results.stream().findFirst();
    }

    // organizationId is always the company (for now: 1). branchGroupId is optional.
    public long createStore(String name, String displayName, String currency, Long branchGroupId) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO stores (name, display_name, currency, organization_id, branch_group_id, active, `public`)" +
                " VALUES (?, ?, ?, 1, ?, TRUE, FALSE)",
                Statement.RETURN_GENERATED_KEYS
            );
            ps.setString(1, name);
            ps.setString(2, displayName);
            ps.setString(3, currency);
            if (branchGroupId != null) ps.setLong(4, branchGroupId); else ps.setNull(4, Types.BIGINT);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    // Returns the company (organization) id for a store.
    public long findCompanyId(long storeId) {
        List<Long> results = jdbcTemplate.query(
            "SELECT organization_id FROM stores WHERE id = ?",
            (rs, i) -> rs.getLong(1), storeId
        );
        if (results.isEmpty()) throw new InvalidRequestException("Store not found: " + storeId);
        return results.get(0);
    }

    public void patch(long storeId, com.fasterxml.jackson.databind.JsonNode body) {
        java.util.List<String> setClauses = new java.util.ArrayList<>();
        java.util.List<Object> params = new java.util.ArrayList<>();

        if (body.has("displayName")) {
            setClauses.add("display_name = ?");
            params.add(body.get("displayName").toString());
        }
        if (body.has("imageResourceId")) {
            setClauses.add("image_resource_id = ?");
            com.fasterxml.jackson.databind.JsonNode img = body.get("imageResourceId");
            params.add(img.isNull() ? null : img.longValue());
        }
        if (body.has("name")) {
            setClauses.add("name = ?");
            params.add(body.get("name").asText());
        }
        if (body.has("currency")) {
            setClauses.add("currency = ?");
            params.add(body.get("currency").asText());
        }
        if (body.has("active")) {
            setClauses.add("active = ?");
            params.add(body.get("active").asBoolean());
        }
        if (body.has("public")) {
            setClauses.add("`public` = ?");
            params.add(body.get("public").asBoolean());
        }

        if (setClauses.isEmpty()) return;
        params.add(storeId);
        jdbcTemplate.update(
            "UPDATE stores SET " + String.join(", ", setClauses) + " WHERE id = ?",
            params.toArray()
        );
    }
}
