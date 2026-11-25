package com.waha.sync;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.EnumMap;
import java.util.Map;

@Repository
public class SyncTableConfigRepository implements SyncTableConfigService.Store {

    private final NamedParameterJdbcTemplate jdbc;

    public SyncTableConfigRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<SyncTable, SyncTableSetting> load(long organizationId) {
        Map<SyncTable, SyncTableSetting> out = new EnumMap<>(SyncTable.class);
        jdbc.query("SELECT table_name, active, expiration_period, description FROM sync_table_config WHERE organization_id = :o",
            Map.of("o", organizationId),
            rs -> {
                // A row with an unknown name is ignored.
                SyncTable.byKey(rs.getString("table_name")).ifPresent(t -> {
                    try {
                        out.put(t, new SyncTableSetting(rs.getBoolean("active"), rs.getString("expiration_period"), rs.getString("description")));
                    } catch (java.sql.SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
            });
        return out;
    }

    @Override
    public void save(long organizationId, SyncTable table, SyncTableSetting s) {
        jdbc.update(
            "INSERT INTO sync_table_config (organization_id, table_name, active, expiration_period, description) " +
            "VALUES (:o, :t, :a, :p, :d) " +
            "ON DUPLICATE KEY UPDATE active = :a, expiration_period = :p, description = :d",
            new MapSqlParameterSource("o", organizationId).addValue("t", table.key())
                .addValue("a", s.active()).addValue("p", s.expirationPeriod()).addValue("d", s.description()));
    }
}
