package com.manara.backend.email.template;

import com.manara.backend.email.model.InlineImage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads classpath images as Base64 {@link InlineImage}s for embedding in email bodies.
 *
 * <p>Encoding is cached: brand assets are small, immutable, and attached to every message, so
 * re-reading and re-encoding them per send would be pure waste.
 */
@Component
public class EmailImageLoader {

    private final Map<String, String> encodedCache = new ConcurrentHashMap<>();

    public InlineImage load(String resourcePath, String contentId, String contentType) {
        String encoded = encodedCache.computeIfAbsent(resourcePath, EmailImageLoader::encode);
        String fileName = resourcePath.substring(resourcePath.lastIndexOf('/') + 1);
        return new InlineImage(contentId, fileName, contentType, encoded);
    }

    private static String encode(String resourcePath) {
        try (InputStream stream = new ClassPathResource(resourcePath).getInputStream()) {
            return Base64.getEncoder().encodeToString(stream.readAllBytes());
        } catch (IOException ex) {
            throw new IllegalStateException("Email image not found on the classpath: " + resourcePath, ex);
        }
    }
}
