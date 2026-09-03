package com.waha.auth;

public record User(long id, String username, String accountType, boolean enabled,
                   String firstName, String lastName, String phone) {}
