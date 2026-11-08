package com.waha.invoice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InvoiceWaitingPageTest {

    @Test
    void startsWaitingAndKeepsTheMarkerAndStartTimeOnReload() {
        String html = InvoiceController.waitingPage(false, null, 1000L);
        assertTrue(html.contains("http-equiv=\"refresh\""));
        assertTrue(html.contains("offline=1"));
        assertTrue(html.contains("since=1000"));
        assertTrue(html.contains("being prepared"));
    }

    @Test
    void stillWaitingJustInsideThirtyMinutes() {
        assertTrue(InvoiceController.waitingPage(true, 1000L, 1000L + 1800).contains("refresh"));
    }

    @Test
    void givesUpAfterThirtyMinutesWithoutReloading() {
        String html = InvoiceController.waitingPage(false, 1000L, 1000L + 1801);
        assertFalse(html.contains("refresh"));
        assertTrue(html.contains("couldn't find"));
    }

    @Test
    void aStartTimeInTheFutureIsIgnored() {
        assertTrue(InvoiceController.waitingPage(false, 999999L, 1000L).contains("since=1000"));
    }
}
