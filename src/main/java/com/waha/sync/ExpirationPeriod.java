package com.waha.sync;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// The expiration period of a sync table: how long a device may go without checking it again.
// Format <number><S|M|H|D>, for example 30S, 5M, 2H, 1D. From 10 seconds to 30 days; removals at most 7 days, because removal
// records are only kept for at least 30 days.
public final class ExpirationPeriod {

    static final long MIN_SECONDS = 10;
    static final long MAX_SECONDS = 30L * 86400;
    static final long REMOVALS_MAX_SECONDS = 7L * 86400;
    private static final Pattern FORMAT = Pattern.compile("^([1-9][0-9]{0,6})([SMHD])$");

    private ExpirationPeriod() {}

    // Seconds for a valid period; IllegalArgumentException with a plain message otherwise.
    public static long seconds(SyncTable table, String period) {
        Matcher m = FORMAT.matcher(period == null ? "" : period.trim().toUpperCase());
        if (!m.matches()) throw new IllegalArgumentException("expirationPeriod must look like 30S, 5M, 2H or 1D");
        long n = Long.parseLong(m.group(1));
        long unit = switch (m.group(2)) {
            case "S" -> 1L;
            case "M" -> 60L;
            case "H" -> 3600L;
            default -> 86400L;
        };
        long seconds = n * unit;
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS)
            throw new IllegalArgumentException("expirationPeriod must be from 10S to 30D");
        if (table == SyncTable.REMOVALS && seconds > REMOVALS_MAX_SECONDS)
            throw new IllegalArgumentException("expirationPeriod of removals must be at most 7D");
        return seconds;
    }

    // Upper-case form that is stored.
    public static String normalize(String period) {
        return period.trim().toUpperCase();
    }
}
