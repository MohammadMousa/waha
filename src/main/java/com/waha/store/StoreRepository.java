package com.waha.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.common.InvalidRequestException;
import com.waha.store.dto.StoreSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
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

    // storeId is always explicit now (from a request, not server config) -
    // see OrderService and ProductController.
    // Uses query() not queryForObject() so an unknown/invalid storeId
    // (including the frontend's storeId=0 fallback sentinel) produces a
    // clean 400 via InvalidRequestException rather than a 500 from
    // EmptyResultDataAccessException bubbling uncaught.
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

    // The system-configured fallback store, read from system_properties.
    // Returned with every auth response (login/register/guest/me) so the
    // frontend always has a usable storeId without a separate round-trip.
    // Returns empty if no default is configured (admin hasn't set it yet).
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

    // The store picker after login (see StoreController). `public` alone is
    // the authoritative signal here (per its definition: never true for a
    // PARENT grouping node or a WAREHOUSE) - no need to redundantly also
    // filter by store_type/store_kind.
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

    // All active stores reachable downward from rootStoreId (itself + every
    // descendant). Used by GET /api/stores/admin so admins can see their
    // full store tree, including non-public grouping nodes.
    public List<StoreSummary> findAdminStores(long rootStoreId) {
        return jdbcTemplate.query(
            "WITH RECURSIVE scope AS (" +
            "  SELECT id, name, display_name, currency, image_resource_id, parent_store_id" +
            "  FROM stores WHERE id = ? AND active = TRUE" +
            "  UNION ALL" +
            "  SELECT s.id, s.name, s.display_name, s.currency, s.image_resource_id, s.parent_store_id" +
            "  FROM stores s JOIN scope p ON s.parent_store_id = p.id WHERE s.active = TRUE" +
            ") SELECT id, name, display_name, currency, image_resource_id FROM scope ORDER BY id",
            (rs, i) -> {
                String rawJson = rs.getString("display_name");
                JsonNode displayName = parseJsonOrNull(rawJson);
                long imgId = rs.getLong("image_resource_id");
                Long imageResourceId = rs.wasNull() ? null : imgId;
                return new StoreSummary(rs.getLong("id"), rs.getString("name"), displayName, rs.getString("currency"), imageResourceId);
            },
            rootStoreId
        );
    }

    // The store where this user's highest admin role is directly assigned.
    // "Highest" = shortest path (closest to tree root). Used to determine
    // the root of their admin realm for GET /api/stores/admin.
    public Optional<Long> findAdminRootStore(long userId) {
        List<Long> results = jdbcTemplate.query(
            "SELECT ur.store_id FROM user_roles ur" +
            " JOIN roles r ON ur.role_id = r.id" +
            " JOIN stores s ON ur.store_id = s.id" +
            " WHERE ur.user_id = ? AND r.name IN ('ADMIN', 'SUPER_ADMIN')" +
            " ORDER BY CHAR_LENGTH(COALESCE(s.path, '')) ASC, ur.store_id ASC LIMIT 1",
            (rs, i) -> rs.getLong(1),
            userId
        );
        return results.stream().findFirst();
    }

    // Used by POST /api/auth/store to reject pointing a session at a
    // grouping node or warehouse - same "public" signal as the picker
    // above, checked again here since this is a different, freeform
    // request path (a client could send any id, not just one it got from
    // the picker).
    public boolean isSelectable(long storeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = ? AND public = TRUE AND active = TRUE",
            Integer.class, storeId
        );
        return count != null && count > 0;
    }

    // Like isSelectable but for admins: only requires the store to be active.
    // Used by POST /api/auth/store when the caller has MANAGE_STORES — they
    // can point their session at any active store, including non-public ones.
    public boolean isAdminSelectable(long storeId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stores WHERE id = ? AND active = TRUE",
            Integer.class, storeId
        );
        return count != null && count > 0;
    }

    public java.util.Optional<StoreSummary> findById(long storeId) {
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

    public java.util.Optional<StoreAdminDetail> findByIdAdmin(long storeId) {
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

    // Returns the requesting store's own id followed by every ancestor, in
    // priority order (most specific first, tree root last) - e.g. for a
    // branch under Root -> Company -> City: [branchId, cityId, companyId,
    // rootId]. `path` holds ancestors root-to-immediate-parent (per
    // V1__core_schema.sql), so this reverses it and prepends the store's
    // own id. Used everywhere scope admissibility needs checking - product
    // resolution, catalog sync - so there's exactly one place that
    // understands how to walk the hierarchy, not one per caller.
    public List<Long> resolveScopeChain(long storeId) {
        List<String> rows = jdbcTemplate.query(
            "SELECT path FROM stores WHERE id = ?",
            (rs, i) -> rs.getString("path"),
            storeId
        );
        if (rows.isEmpty()) {
            throw new InvalidRequestException("Store not found: " + storeId);
        }
        String path = rows.get(0);

        List<Long> chain = new ArrayList<>();
        chain.add(storeId);

        if (path != null && !path.isBlank()) {
            String[] ancestors = path.split("/");
            for (int i = ancestors.length - 1; i >= 0; i--) {
                chain.add(Long.parseLong(ancestors[i]));
            }
        }

        return chain;
    }
}
