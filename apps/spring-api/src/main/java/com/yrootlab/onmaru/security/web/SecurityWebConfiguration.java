package com.yrootlab.onmaru.security.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SecurityWebConfiguration implements WebMvcConfigurer {

    private final PrivateResponseInterceptor privateResponseInterceptor;

    SecurityWebConfiguration(PrivateResponseInterceptor privateResponseInterceptor) {
        this.privateResponseInterceptor = privateResponseInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(privateResponseInterceptor);
    }
}
