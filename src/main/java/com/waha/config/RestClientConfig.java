package com.waha.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

// Spring Boot doesn't auto-configure a RestTemplate bean by default - this
// is the one line needed to make it injectable, ported from
// commerce-platform's own RestClientConfig.
@Configuration
public class RestClientConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
