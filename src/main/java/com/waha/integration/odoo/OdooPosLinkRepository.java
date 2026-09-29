package com.waha.integration.odoo;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class OdooPosLinkRepository {

    public static final String BRANCH = "BRANCH";
    public static final String PAYMENT_METHOD = "PAYMENT_METHOD";

    public record PosLink(String linkType, String localKey, long odooId) {}

    private final NamedParameterJdbcTemplate jdbc;

    public OdooPosLinkRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Long> findOdooId(long systemId, String linkType, String localKey) {
        List<Long> ids = jdbc.query(
            "SELECT odoo_id FROM odoo_pos_links WHERE system_id = :sid AND link_type = :type AND local_key = :key",
            Map.of("sid", systemId, "type", linkType, "key", localKey),
            (rs, i) -> rs.getLong(1));
        return ids.stream().findFirst();
    }

    public List<PosLink> findAll(long systemId) {
        return jdbc.query(
            "SELECT link_type, local_key, odoo_id FROM odoo_pos_links WHERE system_id = :sid ORDER BY link_type, local_key",
            Map.of("sid", systemId),
            (rs, i) -> new PosLink(rs.getString(1), rs.getString(2), rs.getLong(3)));
    }

    public List<Long> findOdooIds(long systemId, String linkType) {
        return jdbc.query(
            "SELECT DISTINCT odoo_id FROM odoo_pos_links WHERE system_id = :sid AND link_type = :type",
            Map.of("sid", systemId, "type", linkType),
            (rs, i) -> rs.getLong(1));
    }

    public void save(long systemId, String linkType, String localKey, long odooId) {
        jdbc.update(
            "INSERT INTO odoo_pos_links (system_id, link_type, local_key, odoo_id) VALUES (:sid, :type, :key, :oid) " +
            "ON DUPLICATE KEY UPDATE odoo_id = VALUES(odoo_id)",
            Map.of("sid", systemId, "type", linkType, "key", localKey, "oid", odooId));
    }

    public void delete(long systemId, String linkType, String localKey) {
        jdbc.update(
            "DELETE FROM odoo_pos_links WHERE system_id = :sid AND link_type = :type AND local_key = :key",
            Map.of("sid", systemId, "type", linkType, "key", localKey));
    }
}
