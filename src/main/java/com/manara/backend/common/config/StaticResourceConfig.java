package com.manara.backend.common.config;

import com.manara.backend.common.file.UploadProperties;
import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.common.security.PublicEndpointContribution;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class StaticResourceConfig implements WebMvcConfigurer, PublicEndpointContribution {

    private final UploadProperties uploadProperties;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Reads the same configured directory FileUploadService writes to. These were two
        // separate hardcoded Paths.get("uploads") calls, so the read path and the write path
        // agreed only by coincidence and neither could be pointed at a mounted volume.
        Path uploadDir = Paths.get(uploadProperties.dir()).toAbsolutePath().normalize();
        String uploadPath = uploadDir.toUri().toString();

        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(uploadPath);
    }

    @Override
    public List<PublicEndpoint> endpoints() {
        return List.of(
                PublicEndpoint.of(HttpMethod.GET, "/uploads/**")
        );
    }
}
