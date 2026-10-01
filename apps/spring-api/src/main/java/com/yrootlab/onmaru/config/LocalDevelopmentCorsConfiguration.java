package com.yrootlab.onmaru.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class LocalDevelopmentCorsConfiguration implements WebMvcConfigurer {

    private final CorsOriginPolicy corsOriginPolicy;

    public LocalDevelopmentCorsConfiguration(CorsOriginPolicy corsOriginPolicy) {
        this.corsOriginPolicy = corsOriginPolicy;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        configure(registry.addMapping("/api/**"));
        configure(registry.addMapping("/auth/csrf"));
    }

    private void configure(CorsRegistration registration) {
        registration.allowedOrigins(corsOriginPolicy.allowedOrigins())
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Idempotency-Key", "X-CSRF-TOKEN", "X-Request-Id")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
