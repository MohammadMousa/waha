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
            "SELECT id, name, display_name, currency FROM stores WHERE public = TRUE AND active = TRUE ORDER BY name",
            (rs, i) -> {
                String rawJson = rs.getString("display_name");
                JsonNode displayName = parseJsonOrNull(rawJson);
                return new StoreSummary(rs.getLong("id"), rs.getString("name"), displayName, rs.getString("currency"));
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
