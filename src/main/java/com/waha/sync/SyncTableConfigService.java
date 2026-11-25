package com.waha.sync;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// Sync table settings per organization, read at most once a minute per organization (changes apply within a minute,
// at once on this server after an admin change). A table without a row is ON with no expiration period.
@Service
public class SyncTableConfigService {

    // Where the rows live (a database table in production, a map in tests).
    public interface Store {
        Map<SyncTable, SyncTableSetting> load(long organizationId);
        void save(long organizationId, SyncTable table, SyncTableSetting setting);
    }

    static final Duration CACHE_FOR = Duration.ofSeconds(60);

    private record Cached(Instant loadedAt, Map<SyncTable, SyncTableSetting> settings) {}

    private final Store store;
    private final Supplier<Instant> clock;
    private final Map<Long, Cached> cache = new ConcurrentHashMap<>();

    @Autowired
    public SyncTableConfigService(SyncTableConfigRepository repository) {
        this(repository, Instant::now);
    }

    SyncTableConfigService(Store store, Supplier<Instant> clock) {
        this.store = store;
        this.clock = clock;
    }

    // Settings of all 7 tables (defaults where there is no row).
    public Map<SyncTable, SyncTableSetting> forOrganization(long organizationId) {
        Instant now = clock.get();
        Cached c = cache.get(organizationId);
        if (c == null || c.loadedAt().plus(CACHE_FOR).isBefore(now)) {
            Map<SyncTable, SyncTableSetting> all = new EnumMap<>(SyncTable.class);
            Map<SyncTable, SyncTableSetting> rows = store.load(organizationId);
            for (SyncTable t : SyncTable.values()) all.put(t, rows.getOrDefault(t, SyncTableSetting.DEFAULT));
            c = new Cached(now, all);
            cache.put(organizationId, c);
        }
        return c.settings();
    }

    public SyncTableSetting get(long organizationId, SyncTable table) {
        return forOrganization(organizationId).get(table);
    }

    // Merge into the current settings. A null "active" keeps it; the period and description change only when "given".
    public void update(long organizationId, SyncTable table, Boolean active,
                       boolean periodGiven, String period, boolean descriptionGiven, String description) {
        SyncTableSetting now = store.load(organizationId).getOrDefault(table, SyncTableSetting.DEFAULT);
        String newPeriod = now.expirationPeriod();
        if (periodGiven) {
            if (period == null || period.isBlank()) newPeriod = null;
            else {
                ExpirationPeriod.seconds(table, period); // validates
                newPeriod = ExpirationPeriod.normalize(period);
            }
        }
        String newDescription = descriptionGiven ? (description == null || description.isBlank() ? null : description.trim()) : now.description();
        if (newDescription != null && newDescription.length() > 255)
            throw new IllegalArgumentException("description must be at most 255 characters");
        store.save(organizationId, table, new SyncTableSetting(active == null ? now.active() : active, newPeriod, newDescription));
        cache.remove(organizationId);
    }
}
