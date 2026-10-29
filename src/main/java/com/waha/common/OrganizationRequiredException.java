package com.waha.common;

// A super-admin (organization 0) tried to write without choosing an organization with the X-Organization-Id header.
public class OrganizationRequiredException extends RuntimeException {
    public static final String CODE = "ORGANIZATION_REQUIRED";

    public OrganizationRequiredException() {
        super("Pick an organization first");
    }
}
