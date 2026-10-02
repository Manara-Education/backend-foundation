package com.manara.backend.privacy.config;

import com.manara.backend.privacy.service.PrivacyPolicyCatalog;
import com.manara.backend.privacy.service.PrivacyPolicyContentLoader;
import com.manara.backend.privacy.service.PrivacyPolicyRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Builds the registry from the shipped catalogue, which validates every version at startup. */
@Configuration
public class PrivacyPolicyConfig {

    @Bean
    public PrivacyPolicyRegistry privacyPolicyRegistry(PrivacyPolicyContentLoader loader) {
        return new PrivacyPolicyRegistry(PrivacyPolicyCatalog.VERSIONS, PrivacyPolicyCatalog.CURRENT_ID, loader);
    }
}
