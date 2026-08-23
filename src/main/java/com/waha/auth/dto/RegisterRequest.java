package com.waha.auth.dto;

import java.util.Map;

public record RegisterRequest(String username, String password, Map<String, String> sessionProperties) {}
