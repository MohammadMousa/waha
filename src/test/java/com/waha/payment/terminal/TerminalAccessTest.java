package com.waha.payment.terminal;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ForbiddenException;
import com.waha.common.UnauthorizedException;
import com.waha.device.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TerminalAccessTest {

    private final SessionService sessions = mock(SessionService.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final TerminalOrderLookup lookup = mock(TerminalOrderLookup.class);
    private final TerminalSessionService service = mock(TerminalSessionService.class);
    private TerminalSessionController controller;

    @BeforeEach
    void setUp() {
        controller = new TerminalSessionController(service, sessions,
            new TerminalAccessGuard(sessions, devices, lookup));
        // A guest or registered account, an employee, and a kiosk device whose saved session store was changed to 99.
        when(sessions.requireSession("user")).thenReturn(new UserSession("user", 7, null, null, 5L, "SHOPPING", 1));
        when(sessions.requireSession("employee")).thenReturn(new UserSession("employee", 0, 3L, null, null, null, 1));
        when(sessions.requireSession("device")).thenReturn(new UserSession("device", 0, null, 4L, 99L, "KIOSK", 1));
        when(sessions.requireSession("none")).thenThrow(new UnauthorizedException("Missing or invalid session"));
        when(devices.findById(4L)).thenReturn(Optional.of(new DeviceRepository.Device(4, 1, 5, "k1", "KIOSK")));
    }

    private void everyCallRefused(String auth, Class<? extends RuntimeException> error) {
        assertThrows(error, () -> controller.create("o1", auth));
        assertThrows(error, () -> controller.get("a1", auth));
        assertThrows(error, () -> controller.pending(auth));
        assertThrows(error, () -> controller.confirm("a1", Map.of("authCode", "x"), auth));
        assertThrows(error, () -> controller.cancel("a1", auth));
        assertThrows(error, () -> controller.lateApproval("o1", Map.of("amount", "10", "rrn", "r"), auth));
    }

    @Test
    void accountAndEmployeeSignInsAreRefusedOnEveryCall() {
        everyCallRefused("user", ForbiddenException.class);
        everyCallRefused("employee", ForbiddenException.class);
        verifyNoInteractions(service);
    }

    @Test
    void noSignInIsRefusedOnEveryCall() {
        everyCallRefused("none", UnauthorizedException.class);
        verifyNoInteractions(service);
    }

    @Test
    void deviceCanCreateConfirmAndCancel() {
        when(lookup.byOrder("o1")).thenReturn(Optional.of(new TerminalOrderLookup.Owner("o1", 5, 1)));
        controller.create("o1", "device");
        controller.confirm("a1", Map.of("authCode", "x"), "device");
        controller.cancel("a1", "device");
        verify(service).create("o1");
        verify(service).confirm("a1", "x", null);
        verify(service).cancel("a1");
    }

    @Test
    void anotherStoreIsOnlyLoggedForNowOnCreateAndConfirm() {
        var other = Optional.of(new TerminalOrderLookup.Owner("o1", 6, 1));
        when(lookup.byOrder("o1")).thenReturn(other);
        when(lookup.byAttempt("a1")).thenReturn(other);
        controller.create("o1", "device");
        controller.confirm("a1", Map.of("authCode", "x"), "device");
        verify(service).create("o1");
        verify(service).confirm("a1", "x", null);
    }

    @Test
    void readingAnotherStoresAttemptIsNotFound() {
        when(lookup.byAttempt("a1")).thenReturn(Optional.of(new TerminalOrderLookup.Owner("o1", 99, 1)));
        assertEquals(404, controller.get("a1", "device").getStatusCode().value());
        verifyNoInteractions(service);
    }

    @Test
    void pendingUsesTheDeviceRecordStoreNotTheSessionStore() {
        when(service.findPending(5, 1)).thenReturn(Optional.empty());
        assertEquals(204, controller.pending("device").getStatusCode().value());
        verify(service).findPending(5, 1);
    }
}
