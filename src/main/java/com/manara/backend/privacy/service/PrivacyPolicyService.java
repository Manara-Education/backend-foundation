package com.manara.backend.privacy.service;

import com.manara.backend.privacy.dto.PrivacyPolicyResponse;
import com.manara.backend.privacy.exception.PrivacyPolicyNotPublishedException;
import com.manara.backend.privacy.mapper.PrivacyPolicyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Serves the published privacy policy, and only the published one. */
@Service
@RequiredArgsConstructor
public class PrivacyPolicyService {

    private final PrivacyPolicyRegistry registry;
    private final PrivacyPolicyMapper mapper;

    public PrivacyPolicyResponse current() {
        return registry.current()
                .map(mapper::toResponse)
                .orElseThrow(() -> new PrivacyPolicyNotPublishedException("error.privacyPolicy.notPublished"));
    }
}
