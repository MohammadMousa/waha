package com.waha.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// No AuthInterceptor here (unlike commerce-platform's CorsConfig) - Waha's
// physical-product flow has no customer accounts at all in Phase 1. If a
// Phase-3 admin/back-office surface is added later, gate it the same way
// commerce-platform gates /api/admin/**, not by adding auth to this flow.
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${waha.cors.allowed-origins:*}")
    private String allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
            .allowedOriginPatterns(allowedOrigins.split(","))
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Authorization", "Content-Type", "Accept")
            .exposedHeaders("Authorization");
    }
}
