package com.aipaas.anycloud.configuration.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** CORS — {@code anycloud.cors.allowed-origins} (CSV) 또는 dev fallback (localhost-only). */
@Slf4j
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /** 허용 origin CSV. 빈 값 → dev fallback (localhost only). */
    @Value("${anycloud.cors.allowed-origins:}")
    private String allowedOriginsCsv;

    /** 허용 method CSV. */
    @Value("${anycloud.cors.allowed-methods:GET,POST,PUT,PATCH,DELETE,OPTIONS,HEAD}")
    private String allowedMethodsCsv;

    /** preflight cache 초. */
    @Value("${anycloud.cors.max-age-seconds:3600}")
    private long maxAgeSeconds;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = parseCsv(allowedOriginsCsv);
        String[] methods = parseCsv(allowedMethodsCsv);

        var mapping = registry.addMapping("/**")
                .allowedMethods(methods)
                .allowedHeaders("*")
                .exposedHeaders("Location", "Idempotency-Key", "X-Request-Id")
                .allowCredentials(true)
                .maxAge(maxAgeSeconds);

        if (origins.length > 0) {
            mapping.allowedOrigins(origins);
        } else {
            log.warn("*** anycloud.cors.allowed-origins NOT SET *** falling back to localhost-only "
                    + "dev pattern. Set explicit origins before production deployment.");
            // allowedOriginPatterns: wildcard + credentials 안전 매칭.
            mapping.allowedOriginPatterns("http://localhost:[*]", "http://127.0.0.1:[*]", "https://localhost:[*]");
        }
    }

    private static String[] parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return new String[0];
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
