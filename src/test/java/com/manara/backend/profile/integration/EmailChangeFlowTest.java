package com.manara.backend.profile.integration;

import com.jayway.jsonpath.JsonPath;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.manara.backend.session.security.SignedIn.signedIn;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The email-change lifecycle end to end: the database, the session layer and the outgoing mail.
 *
 * <p>The code is never stored, so these tests read it the way a person would — from the message
 * sent to the new address, captured at the email service. Every address here is synthetic.
 */
class EmailChangeFlowTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@emailchange.example";
    private static final String PASSWORD = "Quiet orchard kettle 97!";
    private static final String BASE = "/api/v1/profile/email/change-requests";
    private static final Pattern CODE = Pattern.compile("\\b(\\d{6})\\b");

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        clearInvocations(emailService);
        given(emailService.send(any())).willReturn(new EmailSendResult("stub"));
    }

    @AfterEach
    void removeTestAccounts() {
        jdbc.update("DELETE FROM email_change_requests WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                "%" + DOMAIN);
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ── Starting ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A wrong current password creates nothing and sends nothing")
    void wrongPasswordIsRefused() throws Exception {
        User account = account("wrongpw");

        start(signedIn(account), "Not the password 1!", "new-wrongpw" + DOMAIN)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_PASSWORD_INVALID"));

        assertThat(requestCount(account)).isZero();
        verify(emailService, after(300).never()).send(any());
    }

    @Test
    @DisplayName("The current address and a malformed address are refused")
    void sameOrMalformedAddressIsRefused() throws Exception {
        User account = account("same");

        start(signedIn(account), PASSWORD, "  SAME" + DOMAIN.toUpperCase() + " ")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_SAME_ADDRESS"));
        start(signedIn(account), PASSWORD, "not-an-address")
                .andExpect(status().isBadRequest());
        assertThat(requestCount(account)).isZero();
    }

    @Test
    @DisplayName("A taken address is answered like any other, and no code is sent to anybody")
    void takenAddressIsIndistinguishable() throws Exception {
        User account = account("seeker");
        account("owner");
        User other = account("control");

        String taken = body(start(signedIn(account), PASSWORD, "owner" + DOMAIN).andExpect(status().isOk()));
        String free = body(start(signedIn(other), PASSWORD, "free-control" + DOMAIN).andExpect(status().isOk()));

        // Same fields, same shapes: nothing in the answer says which address was registered.
        assertThat(keysOf(taken)).isEqualTo(keysOf(free));
        assertThat((String) JsonPath.read(taken, "$.data.maskedEmail")).isEqualTo("ow***" + DOMAIN);
        // Relative deadlines: ten minutes for the code, sixty seconds before a resend.
        assertThat(((Number) JsonPath.read(free, "$.data.expiresInSeconds")).longValue()).isBetween(595L, 600L);
        assertThat(((Number) JsonPath.read(free, "$.data.resendAvailableInSeconds")).longValue()).isBetween(55L, 60L);
        verify(emailService, timeout(5_000)).send(argThat(message -> message.to().equals("free-control" + DOMAIN)));
        verify(emailService, after(500).never()).send(argThat(message -> message.to().equals("owner" + DOMAIN)));
    }

    // ── Completing ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A correct code moves the account, ends other sessions, and notifies the old address")
    void successfulChange() throws Exception {
        User account = account("mover");
        String newEmail = "moved" + DOMAIN;

        String requestId = requestIdOf(start(signedIn(account), PASSWORD, newEmail).andExpect(status().isOk()));
        String code = codeSentTo(newEmail);

        MvcResult verified = verifyCode(signedIn(account), requestId, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(newEmail))
                .andExpect(jsonPath("$.data.emailVerified").value(true))
                .andReturn();

        assertThat(emailOf(account)).isEqualTo(newEmail);
        verify(emailService, timeout(5_000)).send(argThat(message -> message.to().equals("mover" + DOMAIN)
                && !message.text().contains(code)));

        // The session the change was made from was replaced by a fresh one, which works.
        MockHttpSession fresh = (MockHttpSession) verified.getRequest().getSession(false);
        mockMvc.perform(get("/api/v1/profile").session(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(newEmail));
        // Any session stamped before the change is refused.
        mockMvc.perform(get("/api/v1/profile").with(signedIn(account)))
                .andExpect(status().isUnauthorized());

        // The code is spent.
        verifyCode(signedInFresh(account), requestId, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));

        // Sign-in follows the address.
        login("mover" + DOMAIN).andExpect(status().isUnauthorized());
        login(newEmail).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A failed notice to the old address does not undo a committed change")
    void noticeFailureKeepsTheChange() throws Exception {
        User account = account("noticefail");
        String newEmail = "noticefail-new" + DOMAIN;
        given(emailService.send(argThat(message -> message != null && message.to().equals("noticefail" + DOMAIN))))
                .willThrow(new IllegalStateException("provider down"));

        String requestId = requestIdOf(start(signedIn(account), PASSWORD, newEmail));
        verifyCode(signedIn(account), requestId, codeSentTo(newEmail)).andExpect(status().isOk());

        verify(emailService, timeout(5_000)).send(argThat(message -> message.to().equals("noticefail" + DOMAIN)));
        assertThat(emailOf(account)).isEqualTo(newEmail);
    }

    @Test
    @DisplayName("An address registered after the request is refused neutrally at verification")
    void uniquenessIsCheckedAtVerification() throws Exception {
        User account = account("racer");
        String contested = "contested" + DOMAIN;

        String requestId = requestIdOf(start(signedIn(account), PASSWORD, contested));
        String code = codeSentTo(contested);
        account("contested");

        verifyCode(signedIn(account), requestId, code)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_UNAVAILABLE"));
        assertThat(emailOf(account)).isEqualTo("racer" + DOMAIN);
    }

    @Test
    @DisplayName("Two simultaneous verifications of one code: exactly one succeeds")
    void concurrentVerificationSucceedsOnce() throws Exception {
        User account = account("twice");
        String newEmail = "twice-new" + DOMAIN;
        String requestId = requestIdOf(start(signedIn(account), PASSWORD, newEmail));
        String code = codeSentTo(newEmail);

        Callable<Integer> attempt = () -> verifyCode(signedIn(account), requestId, code).andReturn()
                .getResponse().getStatus();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> results = pool.invokeAll(List.of(attempt, attempt));
            List<Integer> statuses = results.stream().map(future -> {
                try {
                    return future.get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(emailOf(account)).isEqualTo(newEmail);
    }

    // ── Codes over time ───────────────────────────────────────────────────────

    @Test
    @DisplayName("An expired code, and a request replaced by a newer one, are both refused as expired")
    void expiryAndSupersession() throws Exception {
        User account = account("stale");

        String first = requestIdOf(start(signedIn(account), PASSWORD, "stale-one" + DOMAIN));
        String firstCode = codeSentTo("stale-one" + DOMAIN);
        clearCooldown(account);
        String second = requestIdOf(start(signedIn(account), PASSWORD, "stale-two" + DOMAIN));

        verifyCode(signedIn(account), first, firstCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_EXPIRED"));

        String secondCode = codeSentTo("stale-two" + DOMAIN);
        jdbc.update("UPDATE email_change_requests SET expires_at = now() - interval '1 second' WHERE request_id = ?::uuid", second);
        verifyCode(signedIn(account), second, secondCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_EXPIRED"));
        assertThat(emailOf(account)).isEqualTo("stale" + DOMAIN);
    }

    @Test
    @DisplayName("Resend waits for its cooldown, and a resent code replaces the previous one")
    void resendCooldownAndReplacement() throws Exception {
        User account = account("resender");
        String newEmail = "resender-new" + DOMAIN;
        String requestId = requestIdOf(start(signedIn(account), PASSWORD, newEmail));
        String firstCode = codeSentTo(newEmail);

        resend(signedIn(account), requestId)
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_COOLDOWN"));
        // A new request is held to the same cooldown, so it cannot be used to get around it.
        start(signedIn(account), PASSWORD, "elsewhere" + DOMAIN)
                .andExpect(status().isTooManyRequests());

        clearCooldown(account);
        clearInvocations(emailService);
        resend(signedIn(account), requestId).andExpect(status().isOk());
        String secondCode = codeSentTo(newEmail);

        if (!secondCode.equals(firstCode)) {
            verifyCode(signedIn(account), requestId, firstCode)
                    .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));
        }
        verifyCode(signedIn(account), requestId, secondCode).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Five wrong codes lock the request, even for the right code afterwards")
    void attemptsAreBounded() throws Exception {
        User account = account("guesser");
        String newEmail = "guesser-new" + DOMAIN;
        String requestId = requestIdOf(start(signedIn(account), PASSWORD, newEmail));
        String code = codeSentTo(newEmail);
        String wrong = code.equals("000000") ? "111111" : "000000";

        for (int attempt = 1; attempt <= 4; attempt++) {
            verifyCode(signedIn(account), requestId, wrong)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));
        }
        verifyCode(signedIn(account), requestId, wrong)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_LOCKED"));
        verifyCode(signedIn(account), requestId, code)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_LOCKED"));
        assertThat(emailOf(account)).isEqualTo("guesser" + DOMAIN);
    }

    @Test
    @DisplayName("One account cannot verify, resend or burn another account's request")
    void requestsAreOwnerScoped() throws Exception {
        User owner = account("victim");
        User intruder = account("intruder");
        String newEmail = "victim-new" + DOMAIN;
        String requestId = requestIdOf(start(signedIn(owner), PASSWORD, newEmail));
        String code = codeSentTo(newEmail);

        verifyCode(signedIn(intruder), requestId, code)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));
        clearCooldownFor(requestId);
        resend(signedIn(intruder), requestId)
                .andExpect(jsonPath("$.code").value("EMAIL_CHANGE_CODE_INVALID"));

        assertThat(jdbc.queryForObject("SELECT attempts FROM email_change_requests WHERE request_id = ?::uuid",
                Integer.class, requestId)).isZero();
        assertThat(emailOf(intruder)).isEqualTo("intruder" + DOMAIN);
        verifyCode(signedIn(owner), requestId, code).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Neither the code nor the password is stored")
    void secretsAreNotStored() throws Exception {
        User account = account("storage");
        String newEmail = "storage-new" + DOMAIN;
        start(signedIn(account), PASSWORD, newEmail);
        String code = codeSentTo(newEmail);

        String hash = jdbc.queryForObject("SELECT code_hash FROM email_change_requests WHERE user_id = ?",
                String.class, account.getId());
        assertThat(hash).hasSize(64).doesNotContain(code);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private ResultActions start(RequestPostProcessor who, String password, String newEmail) throws Exception {
        return mockMvc.perform(post(BASE).with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\":\"%s\",\"newEmail\":\"%s\"}".formatted(password, newEmail)));
    }

    private ResultActions verifyCode(RequestPostProcessor who, String requestId, String code) throws Exception {
        return mockMvc.perform(post(BASE + "/verify").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"%s\",\"code\":\"%s\"}".formatted(requestId, code)));
    }

    private ResultActions resend(RequestPostProcessor who, String requestId) throws Exception {
        return mockMvc.perform(post(BASE + "/resend").with(csrf()).with(who)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requestId\":\"%s\"}".formatted(requestId)));
    }

    private ResultActions login(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, PASSWORD)));
    }

    /** Signed in as the account as it now is, after an epoch bump. */
    private RequestPostProcessor signedInFresh(User account) {
        return signedIn(userRepository.findById(account.getId()).orElseThrow());
    }

    private User account(String localPart) {
        return userRepository.save(User.builder()
                .fullName("Email Changer")
                .email(localPart + DOMAIN)
                .password(passwordEncoder.encode(PASSWORD))
                .role(Role.STUDENT)
                .emailVerified(true)
                .build());
    }

    private String codeSentTo(String address) {
        ArgumentCaptor<EmailMessage> sent = ArgumentCaptor.forClass(EmailMessage.class);
        verify(emailService, timeout(5_000).atLeastOnce()).send(sent.capture());
        List<EmailMessage> toAddress = sent.getAllValues().stream()
                .filter(message -> message.to().equals(address))
                .toList();
        assertThat(toAddress).as("a code was sent to " + address).isNotEmpty();
        Matcher matcher = CODE.matcher(toAddress.getLast().text());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private void clearCooldown(User account) {
        jdbc.update("UPDATE email_change_requests SET resend_available_at = now() - interval '1 second' WHERE user_id = ?",
                account.getId());
    }

    private void clearCooldownFor(String requestId) {
        jdbc.update("UPDATE email_change_requests SET resend_available_at = now() - interval '1 second' WHERE request_id = ?::uuid",
                requestId);
    }

    private int requestCount(User account) {
        return jdbc.queryForObject("SELECT count(*) FROM email_change_requests WHERE user_id = ?", Integer.class,
                account.getId());
    }

    private String emailOf(User account) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, account.getId());
    }

    private static String body(ResultActions actions) throws Exception {
        return actions.andReturn().getResponse().getContentAsString();
    }

    private static String requestIdOf(ResultActions actions) throws Exception {
        return JsonPath.read(body(actions), "$.data.requestId");
    }

    private static java.util.Set<String> keysOf(String json) {
        java.util.Map<String, Object> data = JsonPath.read(json, "$.data");
        return data.keySet();
    }
}
