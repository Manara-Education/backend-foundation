package com.manara.backend.terms.integration;

import com.manara.backend.common.security.PublicEndpoint;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.config.TermsSecurityConfig;
import com.manara.backend.terms.service.TermsVersionRegistry;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.UUID;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TECH-14: exactly how much of the terms API anyone can reach without an account, and how much
 * they cannot.
 *
 * <p>{@link TermsVersionEndpointTest} proves the one public read works. This proves the boundary
 * around it: that the read is deterministic and identical signed in or not, that no method other than
 * {@code GET} is accepted on it from anyone, that nothing else under {@code /api/v1/terms} is open,
 * and that the feature's public-endpoint contribution is that single route and nothing broader.
 *
 * <p>It also records, as an executable fact, that there is no version-specific read today —
 * {@code /api/v1/terms/versions/{id}} and similar are unmapped. Whether one is required is an open
 * product decision (see {@code docs/api/TERMS_API.md}); this test is what changes when it is made.
 */
class TermsApiAccessBoundaryTest extends AbstractPostgresBackedTest {

    private static final String CURRENT = "/api/v1/terms/current";

    @Autowired WebApplicationContext context;
    @Autowired UserRepository userRepository;
    @Autowired TermsVersionRegistry registry;

    private MockMvc mockMvc;

    @BeforeEach
    void buildMockMvc() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private User student() {
        return userRepository.save(User.builder()
                .fullName("Terms reader")
                .email("terms-reader-" + UUID.randomUUID() + "@manara.test")
                .password("{noop}irrelevant")
                .emailVerified(true)
                .role(Role.STUDENT)
                .build());
    }

    private String body(org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
        return mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("the current version is the same answer on every read, anonymous or signed in")
    void currentReadIsDeterministic() throws Exception {
        var current = registry.current().orElseThrow();

        String first = body(get(CURRENT));
        String second = body(get(CURRENT));
        String signedInRead = body(get(CURRENT).with(signedIn(student())));

        assertThat(second).isEqualTo(first);
        assertThat(signedInRead).isEqualTo(first);
        mockMvc.perform(get(CURRENT))
                .andExpect(jsonPath("$.data.version").value(current.id()))
                .andExpect(jsonPath("$.data.effectiveDate").value(current.effectiveDate().toString()));
    }

    @ParameterizedTest(name = "anonymous {0} is refused")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void anonymousWritesAreRefused(String method) throws Exception {
        // With a valid CSRF token the request reaches authorization, which refuses it: only GET
        // is public on this path.
        mockMvc.perform(request(HttpMethod.valueOf(method), CURRENT).with(csrf())
                        .contentType("application/json").content("{\"version\":\"9.9\"}"))
                .andExpect(status().isUnauthorized());
        // Without one, CSRF protection refuses it first — unchanged for this path.
        mockMvc.perform(request(HttpMethod.valueOf(method), CURRENT)
                        .contentType("application/json").content("{\"version\":\"9.9\"}"))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest(name = "signed-in {0} finds no write handler")
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void signedInWritesHaveNoHandler(String method) throws Exception {
        String before = body(get(CURRENT));

        mockMvc.perform(request(HttpMethod.valueOf(method), CURRENT).with(signedIn(student())).with(csrf())
                        .contentType("application/json").content("{\"version\":\"9.9\"}"))
                .andExpect(status().isMethodNotAllowed());

        assertThat(body(get(CURRENT))).isEqualTo(before);
    }

    @ParameterizedTest(name = "GET {0} is not public")
    @ValueSource(strings = {"/api/v1/terms", "/api/v1/terms/1.0", "/api/v1/terms/versions/1.0", "/api/v1/terms/current/1.0"})
    void noOtherTermsPathIsPublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest(name = "GET {0} does not exist today")
    @ValueSource(strings = {"/api/v1/terms/versions/1.0", "/api/v1/terms/1.0"})
    @DisplayName("there is no version-specific terms read yet")
    void versionSpecificReadDoesNotExist(String path) throws Exception {
        mockMvc.perform(get(path).with(signedIn(student()))).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the terms feature opens exactly one route to anonymous callers")
    void publicSurfaceIsTheCurrentReadOnly() {
        assertThat(new TermsSecurityConfig().endpoints())
                .containsExactly(PublicEndpoint.of(HttpMethod.GET, CURRENT));
    }

    @Test
    @DisplayName("an anonymous read neither needs nor reveals an account")
    void anonymousReadCarriesNoAccountData() throws Exception {
        String anonymous = body(get(CURRENT));

        assertThat(anonymous).doesNotContain("@").doesNotContainIgnoringCase("user").doesNotContainIgnoringCase("accepted");
        assertThat(List.of("version", "effectiveDate")).allSatisfy(field -> assertThat(anonymous).contains(field));
    }
}
