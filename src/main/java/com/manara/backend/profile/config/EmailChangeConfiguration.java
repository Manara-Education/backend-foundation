package com.manara.backend.profile.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(EmailChangeProperties.class)
public class EmailChangeConfiguration {
}
