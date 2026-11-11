package com.waha.shipping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.address.Address;
import com.waha.address.AddressRepository;
import com.waha.common.InvalidRequestException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// Decides how an online order can be received. Today: pickup, and free delivery to a saved address.
// Rules that switch free delivery on or off (per branch, city or cart value) belong in options(): both the quote
// and the order go through it, so a rule added there is enforced everywhere.
@Service
public class ShippingService {

    public static final String PICKUP = "PICKUP";
    public static final String FREE_SHIPPING = "FREE_SHIPPING";

    private final AddressRepository addresses;
    private final ObjectMapper mapper;

    public ShippingService(AddressRepository addresses, ObjectMapper mapper) {
        this.addresses = addresses;
        this.mapper = mapper;
    }

    // Shipping is for online orders only: a signed-in user account whose session says NORMAL mode. Shopping mode (a guest
    // account included), devices, employees, sessions that did not send a mode and requests without a session never get
    // options and never carry a shipping method.
    public static boolean isOnline(Long userId, String sessionMode) {
        return userId != null && "NORMAL".equals(sessionMode);
    }

    // null outside normal mode. addressId is the address the customer picked (null when none yet).
    public List<ShippingOption> options(long storeId, Long userId, String sessionMode, Long addressId) {
        if (!isOnline(userId, sessionMode)) return null;
        return options(storeId, userId, addressId);
    }

    List<ShippingOption> options(long storeId, Long userId, Long addressId) {
        Address address = addressId == null ? null : ownAddress(userId, addressId);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        return List.of(
            new ShippingOption(PICKUP, zero, false, true, null),
            new ShippingOption(FREE_SHIPPING, zero, true, address != null, address != null ? null : "ADDRESS_REQUIRED"));
    }

    // null when the order carries no shipping: outside normal mode the method and address are ignored.
    public ShippingChoice choose(long storeId, Long userId, String sessionMode, String method, Long addressId) {
        if (!isOnline(userId, sessionMode)) return null;
        return choose(storeId, userId, method, addressId);
    }

    ShippingChoice choose(long storeId, Long userId, String method, Long addressId) {
        if (method == null || method.isBlank()) {
            if (addressId != null) throw new InvalidRequestException("shippingMethod is required with addressId");
            return null;
        }
        String m = method.trim().toUpperCase();
        if (!m.equals(PICKUP) && !m.equals(FREE_SHIPPING))
            throw new InvalidRequestException("shippingMethod must be PICKUP or FREE_SHIPPING");
        if (m.equals(PICKUP)) return new ShippingChoice(PICKUP, BigDecimal.ZERO.setScale(2), null, null);

        if (userId == null) throw new InvalidRequestException("Sign in to order with delivery");
        if (addressId == null) throw new InvalidRequestException("addressId is required for " + FREE_SHIPPING);
        ShippingOption option = options(storeId, userId, addressId).stream()
            .filter(o -> o.method().equals(m)).findFirst().orElseThrow();
        if (!option.available())
            throw new ShippingNotAvailableException("Free delivery is not available for this order");
        return new ShippingChoice(m, option.amount(), addressId, snapshot(ownAddress(userId, addressId)));
    }

    private Address ownAddress(Long userId, long addressId) {
        if (userId == null) throw new InvalidRequestException("Sign in to use a saved address");
        return addresses.find(userId, addressId)
            .orElseThrow(() -> new InvalidRequestException("Address not found: " + addressId));
    }

    private String snapshot(Address a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", a.label());
        m.put("recipientName", a.recipientName());
        m.put("phone", a.phone());
        m.put("buildingNumber", a.buildingNumber());
        m.put("streetName", a.streetName());
        m.put("secondaryNumber", a.secondaryNumber());
        m.put("district", a.district());
        m.put("city", a.city());
        m.put("postalCode", a.postalCode());
        m.put("countryCode", a.countryCode());
        m.put("additionalInfo", a.additionalInfo());
        try {
            return mapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
