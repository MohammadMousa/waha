package com.waha.address;

import java.time.Instant;

// One saved address of a customer.
public record Address(long id, String label, String recipientName, String phone, String buildingNumber,
                      String streetName, String secondaryNumber, String district, String city, String postalCode,
                      String countryCode, String additionalInfo, boolean isDefault, Instant createdAt) {}
