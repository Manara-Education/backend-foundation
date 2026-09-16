package com.manara.backend.contact.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the contact feature's typed configuration, the way {@code EmailConfiguration} does for
 * the email feature.
 */
@Configuration
@EnableConfigurationProperties(ContactProperties.class)
public class ContactConfiguration {
}
