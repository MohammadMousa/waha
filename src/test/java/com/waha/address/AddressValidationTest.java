package com.waha.address;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressValidationTest {

    private static AddressRequest req(String phone, String street, String city, String country) {
        return new AddressRequest(null, null, phone, null, street, null, null, city, null, country, null, null);
    }

    @Test
    void cityStreetAndPhoneAreRequired() {
        assertNull(AddressController.validate(AddressController.clean(req("0500000000", "Olaya", "Riyadh", null))));
        assertEquals("city is required", AddressController.validate(AddressController.clean(req("0500000000", "Olaya", " ", null))));
        assertEquals("streetName is required", AddressController.validate(AddressController.clean(req("0500000000", null, "Riyadh", null))));
        assertEquals("phone is required", AddressController.validate(AddressController.clean(req(null, "Olaya", "Riyadh", null))));
    }

    @Test
    void phoneAndCountryMustLookRight() {
        assertNotNull(AddressController.validate(AddressController.clean(req("abc", "Olaya", "Riyadh", null))));
        assertNull(AddressController.validate(AddressController.clean(req("+966 50 000 0000", "Olaya", "Riyadh", "sa"))));
        assertNotNull(AddressController.validate(AddressController.clean(req("0500000000", "Olaya", "Riyadh", "SAU"))));
    }

    @Test
    void textIsTrimmedAndBlanksBecomeNothing() {
        AddressRequest c = AddressController.clean(new AddressRequest("  ", " Ali ", "0500000000", "", " Olaya ", null, null, " Riyadh", null, null, null, true));
        assertNull(c.label());
        assertEquals("Ali", c.recipientName());
        assertEquals("Olaya", c.streetName());
        assertEquals("Riyadh", c.city());
        assertNull(c.buildingNumber());
    }
}
