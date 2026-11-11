package com.waha.address;

// What a customer sends to add or change an address. city, streetName and phone are required.
// isDefault: true makes this the default address; omitted keeps it as it is (a new address becomes the default when it is the first).
public record AddressRequest(String label, String recipientName, String phone, String buildingNumber,
                             String streetName, String secondaryNumber, String district, String city,
                             String postalCode, String countryCode, String additionalInfo, Boolean isDefault) {}
