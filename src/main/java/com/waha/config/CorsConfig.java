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
        String[] origins = allowedOrigins.split(",");
        String[] methods = {"GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"};
        registry.addMapping("/api/**")
            .allowedOriginPatterns(origins)
            .allowedMethods(methods)
            .allowedHeaders("Authorization", "Content-Type", "Accept")
            .exposedHeaders("Authorization");
        // Public resource serving — no auth headers needed, GET only.
        registry.addMapping("/resource/**")
            .allowedOriginPatterns(origins)
            .allowedMethods("GET", "OPTIONS")
            .allowedHeaders("If-None-Match")
            .exposedHeaders("ETag", "Cache-Control");
    }
}
