package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.terms.service.TermsVersionRegistry;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code aiAccess} as the signed-in user sees it: on sign-in and on {@code /auth/me}.
 *
 * <p>The column is set directly in SQL, because that is the only way it is ever set.
 */
class AiAccessExposureTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@aiaccess.example";
    private static final String EMAIL = "noor" + DOMAIN;
    private static final String PASSWORD = "Sunlit harbour lantern 42!";
    private static final String SESSION_COOKIE = "MANARA_SESSION";

    private MockMvc mockMvc;

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TermsVersionRegistry termsVersionRegistry;

    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    private jakarta.servlet.Filter sessionRepositoryFilter;

    @BeforeEach
    void registerVerifiedAccount() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(sessionRepositoryFilter)
                .apply(springSecurity())
                .build();
        removeTestAccounts();

        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Noor Test","email":"%s","password":"%s","role":"STUDENT",
                                 "termsAccepted":true,"termsVersion":"%s"}
                                """.formatted(EMAIL, PASSWORD,
                                termsVersionRegistry.current().orElseThrow().id())))
                .andExpect(status().isCreated());

        String code = jdbc.queryForObject("""
                SELECT o.code FROM otps o JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false ORDER BY o.created_at DESC LIMIT 1
                """, String.class, EMAIL);
        mockMvc.perform(post("/api/v1/auth/verify-otp").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","code":"%s"}
                                """.formatted(EMAIL, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiAccess").value(false));
    }

    @AfterEach
    void removeTestAccounts() {
        for (String table : new String[]{"terms_acceptances", "otps", "students", "instructors"}) {
            jdbc.update("DELETE FROM " + table
                    + " WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)", "%" + DOMAIN);
        }
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    @Test
    @DisplayName("a new account has no AI access, on sign-in and on /me")
    void offByDefault() throws Exception {
        Cookie session = signIn(false);

        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiAccess").value(false));
    }

    @Test
    @DisplayName("after the column is set, /me and the next sign-in report true")
    void onceGrantedItIsReported() throws Exception {
        Cookie session = signIn(false);

        grant(true);

        // Same session: /me re-reads the row, so the change shows without signing in again.
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiAccess").value(true));

        signIn(true);

        grant(false);
        mockMvc.perform(get("/api/v1/auth/me").cookie(session))
                .andExpect(jsonPath("$.data.aiAccess").value(false));
    }

    @Test
    @DisplayName("aiAccess in a request body is ignored by PUT /profile and POST /auth/register")
    void requestsCannotSetIt() throws Exception {
        Cookie session = signIn(false);

        mockMvc.perform(put("/api/v1/profile").with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Noor Renamed","aiAccess":true}
                                """))
                .andExpect(status().isOk());
        assertThat(storedAccess(EMAIL)).isFalse();

        String other = "mallory" + DOMAIN;
        mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fullName":"Mallory Test","email":"%s","password":"%s","role":"STUDENT",
                                 "termsAccepted":true,"termsVersion":"%s","aiAccess":true}
                                """.formatted(other, PASSWORD,
                                termsVersionRegistry.current().orElseThrow().id())))
                .andExpect(status().isCreated());
        assertThat(storedAccess(other)).isFalse();
    }

    private Boolean storedAccess(String email) {
        return jdbc.queryForObject("SELECT ai_access FROM users WHERE email = ?", Boolean.class, email);
    }

    private void grant(boolean access) {
        assertThat(jdbc.update("UPDATE users SET ai_access = ? WHERE email = ?", access, EMAIL))
                .isOne();
    }

    private Cookie signIn(boolean expectedAiAccess) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(EMAIL, PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.aiAccess").value(expectedAiAccess))
                .andReturn();
        Cookie issued = result.getResponse().getCookie(SESSION_COOKIE);
        assertThat(issued).isNotNull();
        return new Cookie(SESSION_COOKIE, issued.getValue());
    }
}
