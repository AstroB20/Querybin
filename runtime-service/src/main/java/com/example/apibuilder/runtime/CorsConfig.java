package com.example.apibuilder.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Published APIs are read-only and meant to be called from anywhere &mdash; curl, other apps, and the
 * builder's web page, which lives on a different origin. This allows cross-origin GETs to {@code /api/**}.
 */
@Configuration
class CorsConfig implements WebMvcConfigurer {

    private final String allowedOrigins;

    CorsConfig(@Value("${app.cors-allowed-origins:*}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins.split(","))
                .allowedMethods("GET");
    }
}
