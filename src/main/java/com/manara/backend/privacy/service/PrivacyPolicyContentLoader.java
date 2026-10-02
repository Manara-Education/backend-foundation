package com.manara.backend.privacy.service;

import com.manara.backend.privacy.model.PrivacyPolicyContent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Reads a privacy-policy content file from the classpath and fingerprints it.
 *
 * <p>Strict about shape: an unknown property is a failure rather than something silently dropped,
 * because a misspelt key in a legal text ({@code "itmes"}) would otherwise publish a section with
 * a missing list and nobody would notice.
 */
@Component
@RequiredArgsConstructor
public class PrivacyPolicyContentLoader {

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    /** The parsed text and the SHA-256 of the exact bytes it was parsed from. */
    public record LoadedContent(PrivacyPolicyContent content, String sha256) {
    }

    public LoadedContent load(String classpathLocation) {
        Resource resource = resourceLoader.getResource("classpath:" + classpathLocation);
        byte[] bytes;
        try (InputStream in = resource.getInputStream()) {
            bytes = in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Privacy policy content '" + classpathLocation + "' cannot be read", e);
        }

        PrivacyPolicyContent content = objectMapper.readerFor(PrivacyPolicyContent.class)
                .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(bytes);
        return new LoadedContent(content, sha256(bytes));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // Every Java platform is required to provide SHA-256.
            throw new IllegalStateException(e);
        }
    }
}
