package com.manara.backend.common.file;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the upload feature's typed configuration, following the same feature-local pattern as
 * {@code EmailConfiguration} so the application class stays untouched.
 */
@Configuration
@EnableConfigurationProperties(UploadProperties.class)
public class UploadConfiguration {
}
