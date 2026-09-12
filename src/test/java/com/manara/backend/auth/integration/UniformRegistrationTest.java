package com.manara.backend.auth.integration;

import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.terms.service.TermsVersionRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SEC-F05. Registration must not be the membership test that forgot-password and resend-otp stopped
 * being.
 *
 * <p>The pentest of 2026-09-10 found {@code POST /api/v1/auth/register} answering an address that
 * already had an account with 400 "Email is already registered" and a new one with 201.
 * {@link AccountEnumerationTest} compares those two answers. This class checks what has to be true
 * underneath for that comparison to mean anything: the existing account is left exactly as it was,
 * each address is sent the mail it should get and nothing else, people racing for one new address
 * are all answered alike, and a new account's code goes out only once the account is real.
 *
 * <p>Every address is unique to its test. The account-exists notice is throttled per address in
 * Redis and the throttle outlives the test that set it, so a fixed address would have its notice
 * suppressed by whichever earlier test got there first.
 */
class UniformRegistrationTest extends AbstractPostgresBackedTest {

    private static final String DOMAIN = "@uniform-registration.example";

    /** At least fifteen characters: the password policy's floor. */
    private static final String PASSWORD = "quiet orchard lantern 7";

    /** What a stranger submits for somebody else's address. None of it may reach that account. */
    private static final String INTRUDER_NAME = "Another Person";
    private static final String INTRUDER_PASSWORD = "copper kettle morning 9";

    private static final int CONTENDERS = 8;

    private MockMvc mockMvc;
    private String termsVersion;

    /** Every message handed to the mail provider, with the thread and the moment it was handed over. */
    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    /** For reads that must not join a transaction the calling thread might be holding. */
    @Autowired
    private DataSource dataSource;

    @Autowired
    private MessageSource messageSource;

    @Autowired
    private TermsVersionRegistry termsVersionRegistry;

    @BeforeEach
    void buildMockMvcAndRecordMail() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        termsVersion = termsVersionRegistry.current().orElseThrow().id();
        recordMail();
    }

    @AfterEach
    void removeTestAccounts() {
        for (String table : List.of("terms_acceptances", "otps", "students", "instructors")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id IN (SELECT id FROM users WHERE email LIKE ?)",
                    "%" + DOMAIN);
        }
        jdbc.update("DELETE FROM users WHERE email LIKE ?", "%" + DOMAIN);
    }

    // ── What the existing account is left with ────────────────────────────────

    @Test
    @DisplayName("an existing and a new address get the same answer, and the existing account is untouched")
    void existingAndNewAreAnsweredAlikeAndNothingIsOverwritten() throws Exception {
        String existing = address("owner");
        createAccount(existing);
        // Verified, so that "the verification flag is untouched" is a claim about a value that could
        // have been knocked back to false, rather than about the default.
        jdbc.update("UPDATE users SET email_verified = true WHERE email = ?", existing);

        Map<String, Object> accountBefore = accountRow(existing);
        Map<String, Integer> rowsBefore = rowsBelongingTo(existing);

        // Everything a stranger controls is different from the owner's: name, password and role.
        String toExisting = answerOf(register(existing, INTRUDER_NAME, INTRUDER_PASSWORD, "INSTRUCTOR"));
        String toNew = answerOf(register(address("newcomer"), INTRUDER_NAME, INTRUDER_PASSWORD, "INSTRUCTOR"));

        assertThat(toExisting)
                .as("an address with an account and one without must be answered identically")
                .isEqualTo(toNew)
                .startsWith("201 ");
        assertThat(accountRow(existing))
                .as("hash, name, role, verification flag and auth version must all be as they were")
                .isEqualTo(accountBefore);
        assertThat(rowsBelongingTo(existing))
                .as("no consent, profile or OTP row may have been added for the existing account")
                .isEqualTo(rowsBefore);
    }

    // ── What each address is sent ─────────────────────────────────────────────

    @Test
    @DisplayName("the existing address is told once, with no code in it, and not again inside the window")
    void theExistingAddressIsToldOnceAndThenThrottled() throws Exception {
        String existing = address("told");
        createAccount(existing);

        register(existing, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT");

        List<Sent> notices = awaitMail(noticeTo(existing), 1);
        assertThat(notices).as("the owner must be told that somebody tried").hasSize(1);
        EmailMessage notice = notices.getFirst().message();
        assertThat(notice.text())
                .as("the notice is guidance: it must carry no code and no link")
                .doesNotContainPattern("\\d{6}")
                .doesNotContain("http")
                .contains(message("auth.email.accountExists.signIn"));

        register(existing, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT");

        // Absence of an asynchronous event can only be shown by waiting for it. The notice would be
        // queued before the response returned and a mock accepts it at once, so a second is far
        // longer than it would need.
        TimeUnit.SECONDS.sleep(1);
        assertThat(matching(noticeTo(existing)))
                .as("a second attempt inside the throttle window must send nothing")
                .hasSize(1);
    }

    @Test
    @DisplayName("a new address is sent its code off the request thread, once the account is committed")
    void aNewAddressIsSentItsCodeAfterCommit() throws Exception {
        String newcomer = address("newcomer");

        assertThat(answerOf(register(newcomer, "New Comer", PASSWORD, "STUDENT"))).startsWith("201 ");

        List<Sent> mail = awaitMail(sentTo(newcomer), 1);
        assertThat(mail).as("a new address gets its code and nothing else").hasSize(1);
        Sent code = mail.getFirst();
        assertThat(code.message().subject()).isEqualTo(message("email.otp.verification.subject"));
        assertThat(code.thread())
                .as("the provider must be called from the dispatch pool, not the request thread")
                .startsWith("email-dispatch-");
        assertThat(code.committedAccounts())
                .as("the account must already be visible to another connection when its code is sent")
                .isEqualTo(1);

        // And the code works. Uniformity was not bought by breaking verification.
        String otp = outstandingCode(newcomer, "EMAIL_VERIFICATION");
        assertThat(code.message().text()).contains(otp);
        assertThat(answerOf(verifyOtp(newcomer, otp))).startsWith("200 ");
        assertThat(jdbc.queryForObject("SELECT email_verified FROM users WHERE email = ?", Boolean.class,
                newcomer)).isTrue();
    }

    // ── Racing for one new address ────────────────────────────────────────────

    @Test
    @DisplayName("eight simultaneous registrations of one new address all get the ordinary answer")
    void racingForOneNewAddressDisclosesNothing() throws Exception {
        String contested = address("contested");
        String ordinary = answerOf(register(address("reference"), "Racer", PASSWORD, "STUDENT"));

        List<String> answers = runTogether(() -> answerOf(register(contested, "Racer", PASSWORD, "STUDENT")));

        assertThat(answers)
                .as("losing the race must look exactly like winning it: no 400, no 409, no 500")
                .hasSize(CONTENDERS)
                .containsOnly(ordinary);
        assertThat(accountCount(contested)).as("exactly one account gets through").isEqualTo(1);
        // Every loser is the existing-account case, so the address gets one code and one notice.
        assertThat(awaitMail(noticeTo(contested), 1)).hasSize(1);
        assertThat(matching(sentTo(contested).and(isNotice().negate())))
                .as("only the winner issues a code")
                .hasSize(1);
    }

    @Test
    @DisplayName("a registration refused by the unique index is answered like any other")
    void losingTheRaceAtTheDatabaseIsAnsweredAlike() throws Exception {
        String contested = address("held");
        String ordinary = answerOf(register(address("reference"), "Racer", PASSWORD, "STUDENT"));

        // The winner, played by hand so the race is certain rather than likely. Its row is inserted
        // but not committed: the registration below finds no account, inserts its own, and waits on
        // the unique index. Committing fails that insert, which is the one path that reaches the
        // database's refusal instead of the application's own existence check.
        try (Connection winner = dataSource.getConnection()) {
            winner.setAutoCommit(false);
            insertAccountBehindTheApplicationsBack(winner, contested);

            ExecutorService requestThread = Executors.newSingleThreadExecutor();
            try {
                Future<String> loser = requestThread.submit(() ->
                        answerOf(register(contested, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT")));
                awaitABlockedInsert();
                winner.commit();

                assertThat(loser.get(30, TimeUnit.SECONDS))
                        .as("the refused insert must be answered with the ordinary response")
                        .isEqualTo(ordinary);
            } finally {
                requestThread.shutdownNow();
            }
        }

        assertThat(accountCount(contested)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT full_name FROM users WHERE email = ?", String.class, contested))
                .as("the account that won is the one that stays")
                .isEqualTo("Held By Hand");
        assertThat(awaitMail(noticeTo(contested), 1))
                .as("the loser is the existing-account case and the owner is told")
                .hasSize(1);
    }

    // ── When the mail provider is down ────────────────────────────────────────

    @Test
    @DisplayName("an outage answers both arms alike and no longer undoes the new account")
    void anOutageLeavesAnAccountThatCanStillBeVerified() throws Exception {
        String existing = address("outage-existing");
        createAccount(existing);
        String newcomer = address("outage-new");

        // Stubbed with the willX().given() form throughout this class: given(mock.send(...)) calls
        // the mock while stubbing it, which would run the recorder below with a null message.
        willThrow(new EmailDeliveryException("error.email.deliveryFailed")).given(emailService).send(any());

        assertThat(answerOf(register(existing, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT")))
                .as("an outage must not tell the two arms apart")
                .isEqualTo(answerOf(register(newcomer, "New Comer", PASSWORD, "STUDENT")))
                .startsWith("201 ");

        // The documented change in behaviour. A failed send used to roll the registration back;
        // now the account stays, unverified, and its owner asks for another code.
        assertThat(accountCount(newcomer)).as("the account survives a failed send").isEqualTo(1);

        recordMail();
        assertThat(answerOf(resendOtp(newcomer))).startsWith("200 ");
        String code = outstandingCode(newcomer, "EMAIL_VERIFICATION");
        assertThat(awaitMail(sentTo(newcomer), 1).getFirst().message().text()).contains(code);
        assertThat(answerOf(verifyOtp(newcomer, code))).startsWith("200 ");
    }

    // ── The flows the existing account still needs ────────────────────────────

    @Test
    @DisplayName("after somebody tries its address, the existing account can still recover and verify")
    void theExistingAccountKeepsItsRecoveryAndVerification() throws Exception {
        String unverified = address("unverified");
        createAccount(unverified);
        String verified = address("verified");
        createAccount(verified);
        jdbc.update("UPDATE users SET email_verified = true WHERE email = ?", verified);

        register(unverified, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT");
        register(verified, INTRUDER_NAME, INTRUDER_PASSWORD, "STUDENT");

        // An owner who never verified does what the notice says: asks for a new code.
        assertThat(answerOf(resendOtp(unverified))).startsWith("200 ");
        assertThat(answerOf(verifyOtp(unverified, outstandingCode(unverified, "EMAIL_VERIFICATION"))))
                .startsWith("200 ");

        // An owner who forgot the password does the other thing it says.
        assertThat(answerOf(forgotPassword(verified))).startsWith("200 ");
        assertThat(answerOf(verifyResetOtp(verified, outstandingCode(verified, "PASSWORD_RESET"))))
                .startsWith("200 ");
    }

    // ── Timing, recorded and never asserted ───────────────────────────────────

    /**
     * Evidence, not a test of anything: run with {@code -Dmanara.evidence.timing=true}.
     *
     * <p>Wall-clock time through MockMvc on a shared laptop is far too noisy to assert on, and a
     * threshold loose enough to be stable would say nothing. What this records is whether the two
     * arms are now of the same order — before this change the existing arm skipped bcrypt and every
     * insert and answered in a fraction of the new arm's time.
     */
    @Test
    @EnabledIfSystemProperty(named = "manara.evidence.timing", matches = "true")
    @DisplayName("timing evidence: existing and new addresses in alternating pairs")
    void recordTimingEvidence() throws Exception {
        int pairs = 20;
        List<String> existing = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            String account = address("timing-existing");
            createAccount(account);
            existing.add(account);
        }
        for (int i = 0; i < 5; i++) {
            register(address("timing-warmup"), "Timing", PASSWORD, "STUDENT");
        }

        long[] toExisting = new long[pairs];
        long[] toNew = new long[pairs];
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            // Which arm goes first alternates, so anything drifting over the run is shared.
            if (i % 2 == 0) {
                toExisting[i] = timedRegistration(existing.get(i), answers);
                toNew[i] = timedRegistration(address("timing-new"), answers);
            } else {
                toNew[i] = timedRegistration(address("timing-new"), answers);
                toExisting[i] = timedRegistration(existing.get(i), answers);
            }
        }

        System.out.printf("[F05-TIMING] pairs=%d existing: median=%.1fms min=%.1fms max=%.1fms"
                        + " | new: median=%.1fms min=%.1fms max=%.1fms | statuses=%s%n",
                pairs, median(toExisting), min(toExisting), max(toExisting),
                median(toNew), min(toNew), max(toNew),
                answers.stream().map(a -> a.substring(0, 3)).distinct().toList());

        assertThat(answers).as("every answer in the run must be the same").containsOnly(answers.getFirst());
    }

    // ------------------------------------------------------------------ requests

    private MvcResult register(String email, String fullName, String password, String role) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/register").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fullName":"%s","email":"%s","password":"%s","role":"%s",
                         "termsAccepted":true,"termsVersion":"%s"}
                        """.formatted(fullName, email, password, role, termsVersion))).andReturn();
    }

    /** An account made through the endpoint, and checked in the table rather than by the status. */
    private void createAccount(String email) throws Exception {
        assertThat(answerOf(register(email, "Original Owner", PASSWORD, "STUDENT"))).startsWith("201 ");
        assertThat(accountCount(email)).as("the fixture account %s must exist", email).isEqualTo(1);
    }

    private MvcResult verifyOtp(String email, String code) throws Exception {
        return postJson("/api/v1/auth/verify-otp", """
                {"email":"%s","code":"%s"}
                """.formatted(email, code));
    }

    private MvcResult resendOtp(String email) throws Exception {
        return postJson("/api/v1/auth/resend-otp", """
                {"email":"%s","type":"EMAIL_VERIFICATION"}
                """.formatted(email));
    }

    private MvcResult forgotPassword(String email) throws Exception {
        return postJson("/api/v1/auth/forgot-password", """
                {"email":"%s"}
                """.formatted(email));
    }

    private MvcResult verifyResetOtp(String email, String code) throws Exception {
        return postJson("/api/v1/auth/verify-reset-otp", """
                {"email":"%s","code":"%s"}
                """.formatted(email, code));
    }

    private MvcResult postJson(String path, String body) throws Exception {
        return mockMvc.perform(post(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn();
    }

    /** Status and body as one string, because an oracle can hide in either half. */
    private static String answerOf(MvcResult result) throws Exception {
        return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private long timedRegistration(String email, List<String> answers) throws Exception {
        long start = System.nanoTime();
        MvcResult result = register(email, "Timing", PASSWORD, "STUDENT");
        long elapsed = System.nanoTime() - start;
        answers.add(answerOf(result));
        return elapsed;
    }

    private <T> List<T> runTogether(Callable<T> work) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(CONTENDERS);
        CyclicBarrier startLine = new CyclicBarrier(CONTENDERS);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < CONTENDERS; i++) {
                futures.add(pool.submit(() -> {
                    startLine.await(10, TimeUnit.SECONDS);
                    return work.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ database

    private static String address(String label) {
        return label + "-" + UUID.randomUUID().toString().substring(0, 8) + DOMAIN;
    }

    private Integer accountCount(String email) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private Map<String, Object> accountRow(String email) {
        return jdbc.queryForMap("""
                SELECT id, full_name, password, role, email_verified, requires_password_reset, auth_version
                  FROM users WHERE email = ?
                """, email);
    }

    private Map<String, Integer> rowsBelongingTo(String email) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : List.of("terms_acceptances", "students", "instructors", "otps")) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table
                    + " WHERE user_id IN (SELECT id FROM users WHERE email = ?)", Integer.class, email));
        }
        return counts;
    }

    /** The code the application generated and emailed. Never exposed by any API. */
    private String outstandingCode(String email, String type) {
        return jdbc.queryForObject("""
                SELECT o.code FROM otps o
                  JOIN users u ON u.id = o.user_id
                 WHERE u.email = ? AND o.used = false AND o.type = ?
                 ORDER BY o.created_at DESC LIMIT 1
                """, String.class, email, type);
    }

    private void insertAccountBehindTheApplicationsBack(Connection connection, String email) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Held By Hand', ?, 'not-a-hash', false, false, 'STUDENT', now())
                """)) {
            insert.setString(1, email);
            insert.executeUpdate();
        }
    }

    /** Waits until some session is blocked on a lock: the registration's insert, on the unique index. */
    private void awaitABlockedInsert() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND datname = current_database()
                    """, Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("the registration never reached the unique index");
    }

    /**
     * How many accounts with this address a separate connection can see. Read on a connection of its
     * own, so that when it is called from inside a transaction it still sees only what is committed.
     */
    private int committedAccounts(String email) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM users WHERE email = ?")) {
            query.setString(1, email);
            try (ResultSet rows = query.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    // ------------------------------------------------------------------ mail

    private record Sent(EmailMessage message, String thread, int committedAccounts) {
    }

    private void recordMail() {
        willAnswer(invocation -> {
            EmailMessage message = invocation.getArgument(0);
            sent.add(new Sent(message, Thread.currentThread().getName(), committedAccounts(message.to())));
            return new EmailSendResult("stub");
        }).given(emailService).send(any());
    }

    private String message(String key) {
        return messageSource.getMessage(key, null, key, Locale.ENGLISH);
    }

    private Predicate<Sent> sentTo(String email) {
        return s -> email.equals(s.message().to());
    }

    private Predicate<Sent> isNotice() {
        String subject = message("auth.email.accountExists.subject");
        return s -> subject.equals(s.message().subject());
    }

    private Predicate<Sent> noticeTo(String email) {
        return sentTo(email).and(isNotice());
    }

    private List<Sent> matching(Predicate<Sent> which) {
        return sent.stream().filter(which).toList();
    }

    /** Dispatch happens after commit on another thread, so mail is waited for rather than expected. */
    private List<Sent> awaitMail(Predicate<Sent> which, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (matching(which).size() < atLeast && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(25);
        }
        return matching(which);
    }

    // ------------------------------------------------------------------ statistics

    private static double median(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        double value = sorted.length % 2 == 0 ? (sorted[mid - 1] + sorted[mid]) / 2.0 : sorted[mid];
        return value / 1_000_000.0;
    }

    private static double min(long[] nanos) {
        return Arrays.stream(nanos).min().orElse(0) / 1_000_000.0;
    }

    private static double max(long[] nanos) {
        return Arrays.stream(nanos).max().orElse(0) / 1_000_000.0;
    }
}
