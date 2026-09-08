package com.manara.backend.terms.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The one thing a visitor with no account has to be able to do before they can get one.
 *
 * <p>The registration form cannot offer anything to accept until it knows what the current terms
 * are, and the person filling it in has no session by definition. So this endpoint being reachable
 * unauthenticated is not a convenience — it is a precondition for anybody ever registering again.
 * Left behind the filter chain it would answer 401 and the sign-up flow would simply stop, which is
 * why it is asserted here rather than assumed from the contribution being present.
 *
 * <p>Driven through the real filter chain ({@code springSecurity()}), with no authentication of any
 * kind attached, so this is the request an anonymous browser actually makes.
 */
class TermsVersionEndpointTest extends AbstractPostgresBackedTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private TermsVersionRegistry registry;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    @DisplayName("an anonymous caller is told the version in force and the date it took effect")
    void currentVersionIsPubliclyReadable() throws Exception {
        var current = registry.current().orElseThrow();

        mockMvc.perform(get("/api/v1/terms/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("success"))
                .andExpect(jsonPath("$.data.version").value(current.id()))
                // ISO-8601, not an epoch number and not a localized rendering: the client displays
                // it and the registration record has to mean the same thing everywhere.
                .andExpect(jsonPath("$.data.effectiveDate").value(current.effectiveDate().toString()));
    }

    @Test
    @DisplayName("the response carries the version and the date, and nothing else")
    void responseIsTheTwoFieldsAndNoMore() throws Exception {
        // The text of the terms lives in the frontend, keyed by this id. If it ever started
        // arriving here instead, every client would have to deal with translating and escaping a
        // legal document — and this endpoint would stop being something an anonymous caller can be
        // handed freely.
        mockMvc.perform(get("/api/v1/terms/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.code").doesNotExist());
    }
}
