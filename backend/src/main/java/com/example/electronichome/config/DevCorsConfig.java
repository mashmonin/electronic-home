package com.example.electronichome.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Allows the local frontend (Vite dev server / nginx) to call the API directly in dev. */
@Configuration(proxyBeanMethods = false)
@Profile("dev")
public class DevCorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173", "https://localhost", "https://localhost:8443")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }
}
