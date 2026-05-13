package com.manara.backend.common.config;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer, PublicEndpointContribution {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path uploadDir = Paths.get("uploads").toAbsolutePath().normalize();
        String uploadPath = uploadDir.toUri().toString();
        
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath);
    }

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.GET, "/uploads/**"),
                PublicEndpoint.of(HttpMethod.GET, "/api/v1/instructors/**"),
                PublicEndpoint.of(HttpMethod.GET, "/swagger-ui/**"),
                PublicEndpoint.of(HttpMethod.GET, "/swagger-ui.html"),
                PublicEndpoint.of(HttpMethod.GET, "/v3/api-docs/**")
        );
    }
}
