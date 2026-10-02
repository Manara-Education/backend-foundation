package com.manara.backend.privacy.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.privacy.model.PrivacyPolicyStatus;
import com.manara.backend.privacy.model.PrivacyPolicyVersion;
import com.manara.backend.privacy.service.PrivacyPolicyContentLoader;
import com.manara.backend.privacy.service.PrivacyPolicyRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published path end to end, against a fixture catalogue: a published 9.0 and a newer candidate
 * 9.1 that the pointer does <em>not</em> name. The response is the published version, with its
 * metadata and structure intact, and the candidate never appears.
 */
class PublishedPrivacyPolicyEndpointTest extends AbstractPostgresBackedTest {

    private static final String PUBLISHED = "legal/privacy/fixture-published-9.0.ar.json";
    private static final String CANDIDATE = "legal/privacy/fixture-candidate-9.1.ar.json";

    @TestConfiguration
    static class FixtureCatalogue {

        @Bean
        @Primary
        PrivacyPolicyRegistry fixtureRegistry(PrivacyPolicyContentLoader loader) {
            return new PrivacyPolicyRegistry(List.of(
                    new PrivacyPolicyVersion("9.0", "ar", PUBLISHED, PrivacyPolicyStatus.PUBLISHED,
                            LocalDate.of(2030, 1, 2), null, sha256(PUBLISHED)),
                    new PrivacyPolicyVersion("9.1", "ar", CANDIDATE, PrivacyPolicyStatus.CANDIDATE,
                            null, "9.0", null)),
                    "9.0", loader);
        }

        private static String sha256(String resource) {
            try (InputStream in = FixtureCatalogue.class.getClassLoader().getResourceAsStream(resource)) {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            } catch (IOException | java.security.NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("an anonymous caller gets the published version with its metadata and sections")
    void publishedVersionIsServed() throws Exception {
        mockMvc.perform(get("/api/v1/privacy-policy/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.policyId").value("privacy-policy"))
                .andExpect(jsonPath("$.data.language").value("ar"))
                .andExpect(jsonPath("$.data.version").value("9.0"))
                .andExpect(jsonPath("$.data.effectiveDate").value("2030-01-02"))
                .andExpect(jsonPath("$.data.supersedes").doesNotExist())
                .andExpect(jsonPath("$.data.title").value("سياسة اختبارية"))
                .andExpect(jsonPath("$.data.operator.name").value("Fixture Operator"))
                .andExpect(jsonPath("$.data.operator.privacyEmail").value("privacy@example.com"))
                .andExpect(jsonPath("$.data.sections.length()").value(2))
                .andExpect(jsonPath("$.data.sections[0].id").value("first"))
                .andExpect(jsonPath("$.data.sections[0].blocks[0].type").value("paragraph"))
                // Markup is data, returned verbatim as text; rendering it as text is the client's job.
                .andExpect(jsonPath("$.data.sections[0].blocks[0].text").value("فقرة <script>alert(1)</script> تبقى نصًا."))
                .andExpect(jsonPath("$.data.sections[0].blocks[0].items").doesNotExist())
                .andExpect(jsonPath("$.data.sections[0].blocks[1].items.length()").value(2))
                .andExpect(jsonPath("$.data.sections[1].blocks[0].type").value("email"))
                .andExpect(jsonPath("$.data.sections[1].blocks[0].address").value("privacy@example.com"));
    }

    @Test
    @DisplayName("the newer candidate is not served while the pointer names the published version")
    void candidateStaysHidden() throws Exception {
        mockMvc.perform(get("/api/v1/privacy-policy/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("9.0"))
                .andExpect(jsonPath("$.data.title").value("سياسة اختبارية"));
    }
}
