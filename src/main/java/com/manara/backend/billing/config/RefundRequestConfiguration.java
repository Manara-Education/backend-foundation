package com.manara.backend.billing.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(RefundRequestProperties.class)
public class RefundRequestConfiguration {
}
