package com.waha.payment.terminal;

import com.waha.auth.SessionService;
import com.waha.auth.UserSession;
import com.waha.common.ForbiddenException;
import com.waha.device.DeviceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// The terminal calls tell the server what a card reader did, so only a kiosk device may use them. The device's store and
// organization come from its device record, never from the store saved in the session (a request can change that one).
@Component
public class TerminalAccessGuard {

    private static final Logger log = LoggerFactory.getLogger(TerminalAccessGuard.class);

    private final SessionService sessionService;
    private final DeviceRepository devices;
    private final TerminalOrderLookup lookup;

    public TerminalAccessGuard(SessionService sessionService, DeviceRepository devices, TerminalOrderLookup lookup) {
        this.sessionService = sessionService;
        this.devices = devices;
        this.lookup = lookup;
    }

    public record Caller(UserSession session, DeviceRepository.Device device) {}

    // 401 without a valid sign-in, 403 for any sign-in that is not a device.
    public Caller requireDevice(String authHeader) {
        UserSession session = sessionService.requireSession(authHeader);
        if (!session.isDeviceSession()) throw new ForbiddenException("A device session is required");
        DeviceRepository.Device device = devices.findById(session.deviceId())
            .orElseThrow(() -> new ForbiddenException("A device session is required"));
        return new Caller(session, device);
    }

    // Only logs for now. Released kiosks can place an order under another store than their device record (they fall back
    // to another public store when their own is not listed), so refusing could stop a real card payment.
    // Make this a refusal once the server log shows no such warnings.
    public void noteOrder(Caller caller, String orderId, String call) {
        lookup.byOrder(orderId).ifPresent(owner -> warnIfOther(caller, owner, call));
    }

    public void noteAttempt(Caller caller, String attemptId, String call) {
        lookup.byAttempt(attemptId).ifPresent(owner -> warnIfOther(caller, owner, call));
    }

    // Used where no released kiosk calls: a device may only see its own store's attempts.
    public boolean ownsAttempt(Caller caller, String attemptId) {
        return lookup.byAttempt(attemptId).map(owner -> sameStore(caller, owner)).orElse(false);
    }

    private static boolean sameStore(Caller caller, TerminalOrderLookup.Owner owner) {
        return owner.storeId() == caller.device().storeId()
            && owner.organizationId() == caller.device().organizationId();
    }

    private void warnIfOther(Caller caller, TerminalOrderLookup.Owner owner, String call) {
        if (sameStore(caller, owner)) return;
        log.warn("Terminal {}: device {} (organization {}, store {}) used order {} of organization {}, store {}",
            call, caller.device().id(), caller.device().organizationId(), caller.device().storeId(),
            owner.orderId(), owner.organizationId(), owner.storeId());
    }
}
