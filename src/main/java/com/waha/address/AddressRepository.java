package com.waha.address;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

@Repository
public class AddressRepository {

    private static final String COLS = "id, label, recipient_name, phone, building_number, street_name, secondary_number, "
        + "district, city, postal_code, country_code, additional_info, is_default, created_at";

    private final JdbcTemplate jdbc;

    public AddressRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Address map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new Address(rs.getLong("id"), rs.getString("label"), rs.getString("recipient_name"), rs.getString("phone"),
            rs.getString("building_number"), rs.getString("street_name"), rs.getString("secondary_number"),
            rs.getString("district"), rs.getString("city"), rs.getString("postal_code"), rs.getString("country_code"),
            rs.getString("additional_info"), rs.getBoolean("is_default"), rs.getTimestamp("created_at").toInstant());
    }

    // Default address first, then newest.
    public List<Address> list(long userId) {
        return jdbc.query("SELECT " + COLS + " FROM addresses WHERE user_id = ? AND deleted_at IS NULL "
            + "ORDER BY is_default DESC, id DESC", AddressRepository::map, userId);
    }

    public Optional<Address> find(long userId, long id) {
        return jdbc.query("SELECT " + COLS + " FROM addresses WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
            AddressRepository::map, id, userId).stream().findFirst();
    }

    public int count(long userId) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM addresses WHERE user_id = ? AND deleted_at IS NULL",
            Integer.class, userId);
        return n == null ? 0 : n;
    }

    @Transactional
    public long insert(long userId, AddressRequest r, String country, boolean makeDefault) {
        if (makeDefault) clearDefault(userId);
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                "INSERT INTO addresses (user_id, label, recipient_name, phone, building_number, street_name, secondary_number, "
                    + "district, city, postal_code, country_code, additional_info, is_default) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS);
            int k = 0;
            ps.setLong(++k, userId);
            ps.setString(++k, r.label());
            ps.setString(++k, r.recipientName());
            ps.setString(++k, r.phone());
            ps.setString(++k, r.buildingNumber());
            ps.setString(++k, r.streetName());
            ps.setString(++k, r.secondaryNumber());
            ps.setString(++k, r.district());
            ps.setString(++k, r.city());
            ps.setString(++k, r.postalCode());
            ps.setString(++k, country);
            ps.setString(++k, r.additionalInfo());
            ps.setBoolean(++k, makeDefault);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    @Transactional
    public void update(long userId, long id, AddressRequest r, String country, boolean makeDefault) {
        if (makeDefault) clearDefault(userId);
        jdbc.update("UPDATE addresses SET label = ?, recipient_name = ?, phone = ?, building_number = ?, street_name = ?, "
                + "secondary_number = ?, district = ?, city = ?, postal_code = ?, country_code = ?, additional_info = ?"
                + (makeDefault ? ", is_default = 1" : "") + " WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
            r.label(), r.recipientName(), r.phone(), r.buildingNumber(), r.streetName(), r.secondaryNumber(), r.district(),
            r.city(), r.postalCode(), country, r.additionalInfo(), id, userId);
    }

    @Transactional
    public void setDefault(long userId, long id) {
        clearDefault(userId);
        jdbc.update("UPDATE addresses SET is_default = 1 WHERE id = ? AND user_id = ? AND deleted_at IS NULL", id, userId);
    }

    // Hides the address. When it was the default, the newest remaining address takes over.
    @Transactional
    public void delete(long userId, long id) {
        jdbc.update("UPDATE addresses SET deleted_at = CURRENT_TIMESTAMP, is_default = 0 WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
            id, userId);
        boolean hasDefault = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM addresses WHERE user_id = ? AND deleted_at IS NULL AND is_default = 1)", Boolean.class, userId));
        if (!hasDefault) {
            jdbc.update("UPDATE addresses SET is_default = 1 WHERE user_id = ? AND deleted_at IS NULL ORDER BY id DESC LIMIT 1", userId);
        }
    }

    private void clearDefault(long userId) {
        jdbc.update("UPDATE addresses SET is_default = 0 WHERE user_id = ? AND is_default = 1", userId);
    }
}
