package com.waha.sync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExpirationPeriodTest {

    @Test
    void readsSecondsMinutesHoursAndDays() {
        assertEquals(30, ExpirationPeriod.seconds(SyncTable.CATEGORIES, "30S"));
        assertEquals(300, ExpirationPeriod.seconds(SyncTable.CATEGORIES, "5M"));
        assertEquals(7200, ExpirationPeriod.seconds(SyncTable.CATEGORIES, "2h"));
        assertEquals(86400, ExpirationPeriod.seconds(SyncTable.CATEGORIES, " 1D "));
    }

    @Test
    void refusesBadFormats() {
        for (String bad : new String[]{"", "  ", "1", "H", "0H", "-1H", "1.5H", "2W", "1 H", "abc", "01H", "99999999H"}) {
            assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.CATEGORIES, bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.CATEGORIES, null));
    }

    @Test
    void rangeIsTenSecondsToThirtyDays() {
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.PRODUCTS, "9S"));
        assertEquals(10, ExpirationPeriod.seconds(SyncTable.PRODUCTS, "10S"));
        assertEquals(30L * 86400, ExpirationPeriod.seconds(SyncTable.PRODUCTS, "30D"));
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.PRODUCTS, "31D"));
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.PRODUCTS, "721H"));
    }

    @Test
    void removalsAreCappedAtSevenDays() {
        assertEquals(7L * 86400, ExpirationPeriod.seconds(SyncTable.REMOVALS, "7D"));
        assertEquals(7L * 86400, ExpirationPeriod.seconds(SyncTable.REMOVALS, "168H"));
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.REMOVALS, "8D"));
        assertThrows(IllegalArgumentException.class, () -> ExpirationPeriod.seconds(SyncTable.REMOVALS, "169H"));
    }

    @Test
    void storedFormIsUpperCase() {
        assertEquals("2H", ExpirationPeriod.normalize(" 2h "));
    }
}
