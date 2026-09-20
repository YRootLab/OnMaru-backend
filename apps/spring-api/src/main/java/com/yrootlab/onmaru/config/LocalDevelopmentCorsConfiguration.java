package com.yrootlab.onmaru.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LocalDevelopmentCorsConfiguration implements WebMvcConfigurer {

    private static final String[] LOCAL_DEVELOPMENT_ORIGINS = {
            "http://localhost:3000",
            "http://localhost:3001",
            "http://localhost:3002",
            "http://localhost:3003",
            "http://localhost:3004",
            "http://localhost:3005",
            "http://localhost:3006",
            "http://localhost:3007"
    };

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(LOCAL_DEVELOPMENT_ORIGINS)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Idempotency-Key", "X-CSRF-TOKEN", "X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
