package com.manara.backend.privacy.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The endpoint as this build ships it: version 1.0 is still a candidate, so an anonymous caller is
 * told plainly that no policy is published — and is never handed the candidate text.
 *
 * <p>Through the real filter chain with no authentication attached, which is what a browser that
 * has never signed in sends.
 */
class PrivacyPolicyEndpointTest extends AbstractPostgresBackedTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("an anonymous caller reaches the endpoint and is told nothing is published yet")
    void candidateIsNotServed() throws Exception {
        mockMvc.perform(get("/api/v1/privacy-policy/current"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.code").value("PRIVACY_POLICY_NOT_PUBLISHED"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("only GET on /current is public; writes and other paths are refused")
    void accessBoundary() throws Exception {
        // No CSRF token: CSRF refuses first.
        mockMvc.perform(post("/api/v1/privacy-policy/current")).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/privacy-policy/current")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/privacy-policy/current")).andExpect(status().isForbidden());
        // Nothing else under the prefix is open to an anonymous caller.
        mockMvc.perform(get("/api/v1/privacy-policy")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/privacy-policy/1.0")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/privacy-policy/versions/1.0")).andExpect(status().isUnauthorized());
    }
}
