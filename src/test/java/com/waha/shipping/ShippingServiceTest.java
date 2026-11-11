package com.waha.shipping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.waha.address.Address;
import com.waha.address.AddressRepository;
import com.waha.common.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShippingServiceTest {

    private final AddressRepository repo = mock(AddressRepository.class);
    private final ShippingService service = new ShippingService(repo, new ObjectMapper());

    private void ownAddress() {
        when(repo.find(5L, 9L)).thenReturn(Optional.of(new Address(9, "Home", "Ali", "0500000000", "1", "Olaya", null, null,
            "Riyadh", null, "SA", null, true, Instant.now())));
    }

    @Test
    void noMethodMeansAnOrderWithoutShipping() {
        assertNull(service.choose(1, null, null, null));
        assertThrows(InvalidRequestException.class, () -> service.choose(1, 5L, null, 9L));
    }

    @Test
    void pickupNeedsNoAddress() {
        ShippingChoice c = service.choose(1, null, "pickup", null);
        assertEquals("PICKUP", c.method());
        assertEquals(0, c.amount().signum());
        assertNull(c.addressSnapshot());
    }

    @Test
    void freeShippingNeedsASignedInUserAndOneOfTheirAddresses() {
        ownAddress();
        assertThrows(InvalidRequestException.class, () -> service.choose(1, null, "FREE_SHIPPING", 9L));
        assertThrows(InvalidRequestException.class, () -> service.choose(1, 5L, "FREE_SHIPPING", null));
        assertThrows(InvalidRequestException.class, () -> service.choose(1, 5L, "FREE_SHIPPING", 10L));
        ShippingChoice c = service.choose(1, 5L, "FREE_SHIPPING", 9L);
        assertEquals(9L, c.addressId());
        assertTrue(c.addressSnapshot().contains("\"city\":\"Riyadh\""));
    }

    @Test
    void optionsShowPickupAlwaysAndDeliveryOnceAnAddressIsPicked() {
        ownAddress();
        var without = service.options(1, 5L, null);
        assertTrue(without.get(0).available());
        assertFalse(without.get(1).available());
        assertEquals("ADDRESS_REQUIRED", without.get(1).reason());
        assertTrue(service.options(1, 5L, 9L).get(1).available());
    }

    @Test
    void onlyNormalModeGetsShipping() {
        ownAddress();
        assertNotNull(service.options(1, 5L, "NORMAL", 9L));
        assertNotNull(service.choose(1, 5L, "NORMAL", "FREE_SHIPPING", 9L));
        for (String mode : new String[]{"SHOPPING", "KIOSK", null}) {
            assertNull(service.options(1, 5L, mode, 9L), "options in " + mode);
            // A method, even a bad one, and an address are ignored outside normal mode.
            assertNull(service.choose(1, 5L, mode, "FREE_SHIPPING", 9L), "choice in " + mode);
            assertNull(service.choose(1, 5L, mode, "FAST", 9L), "bad method in " + mode);
        }
        // Not a user account (device, employee, no session): nothing, even in NORMAL mode.
        assertNull(service.options(1, null, "NORMAL", null));
        assertNull(service.choose(1, null, "NORMAL", "PICKUP", null));
    }
}
