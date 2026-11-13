package com.manara.backend.privacy.mapper;

import com.manara.backend.privacy.dto.PrivacyPolicyResponse;
import com.manara.backend.privacy.model.LoadedPrivacyPolicy;
import com.manara.backend.privacy.model.PrivacyPolicyContent;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;
import org.springframework.stereotype.Component;

/** Builds the response DTO from a loaded version. Pure: no registry, no clock. */
@Component
public class PrivacyPolicyMapper {

    public PrivacyPolicyResponse toResponse(LoadedPrivacyPolicy policy) {
        PrivacyPolicyVersion version = policy.version();
        PrivacyPolicyContent content = policy.content();
        PrivacyPolicyContent.Operator operator = content.operator();

        return new PrivacyPolicyResponse(
                content.policyId(),
                content.language(),
                version.id(),
                version.effectiveDate(),
                version.supersedes(),
                content.title(),
                content.summary(),
                new PrivacyPolicyResponse.Operator(operator.name(), operator.owner(), operator.privacyEmail()),
                content.sections().stream()
                        .map(section -> new PrivacyPolicyResponse.Section(
                                section.id(),
                                section.number(),
                                section.title(),
                                section.blocks().stream()
                                        .map(block -> new PrivacyPolicyResponse.Block(
                                                block.type(), block.text(), block.items(), block.label(), block.address()))
                                        .toList()))
                        .toList());
    }
}
