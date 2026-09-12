package com.manara.backend.auth.service;

import com.manara.backend.auth.email.OtpEmailFactory;
import com.manara.backend.auth.mapper.OtpMapper;
import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.email.service.DeferredEmailDispatcher;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class OtpService {

    private final OtpRepository otpRepository;
    private final OtpMapper otpMapper;
    private final SecureRandom secureRandom;
    private final OtpEmailFactory otpEmailFactory;
    private final DeferredEmailDispatcher deferredEmailDispatcher;
    private final OtpAttemptRecorder attemptRecorder;

    @Value("${otp.expiration-minutes}")
    private int expirationMinutes;

    /**
     * Guesses allowed against a single code before it is burned.
     *
     * <p>A six-digit code has a million possibilities, so five attempts leave a 1-in-200,000
     * chance of a lucky guess per issued code — while comfortably tolerating a user mistyping a
     * code they legitimately received.
     */
    @Value("${otp.max-attempts:5}")
    private int maxAttempts;

    /**
     * Retires the account's outstanding codes of this type, mints one, stores it, and builds the
     * message that carries it. Shared by both dispatch paths so that the code in the database and
     * the code in the email cannot drift apart depending on which one was used.
     */
    private EmailMessage generate(User user, OtpType type) {
        otpRepository.invalidateAllByUserIdAndType(user.getId(), type);

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        var expiresAt = LocalDateTime.now().plusMinutes(expirationMinutes);
        otpRepository.save(otpMapper.toOtp(user, code, type, expiresAt));

        return otpEmailFactory.create(user.getEmail(), code, type, expirationMinutes);
    }

    /**
     * Invalidates any outstanding code of this type, issues a new one, and has it emailed once this
     * transaction commits, without telling the caller anything about the delivery.
     *
     * <p>The generated code is never returned, logged, or exposed by any API — it exists only in
     * this method and in the message handed to the email feature.
     *
     * <p>Every caller is an endpoint an unauthenticated stranger can point at an address:
     * registration, forgot-password and resend-otp. The provider call happens after commit and off
     * the request thread, and a delivery failure is logged rather than raised, because each
     * alternative can be measured. Sending inline costs a few hundred milliseconds only when there is
     * an account to write to, and a provider outage answers only those requests with a 503. Either one
     * re-creates the membership test that unifying the status codes was meant to close, and the second
     * one does it precisely when nobody is watching.
     *
     * <p>There used to be a synchronous variant, and registration used it so that a failed send would
     * roll the new account back. It went when registration stopped saying whether an address was
     * taken: a 503 that only a new address could produce was the same disclosure by another route. A
     * failed send now leaves an unverified account behind, and its owner asks for another code, just
     * as after an email that was lost.
     */
    @Transactional
    public void generateAndSendQuietly(User user, OtpType type) {
        deferredEmailDispatcher.dispatchAfterCommit(generate(user, type));
    }

    /**
     * Resolves the caller's outstanding code and checks the one they supplied against it.
     *
     * <p>The address is canonicalised before the lookup. Callers hand this whatever string the
     * client sent, and {@code otps} is joined to {@code users} on the stored — canonical —
     * address, so an uncanonicalised argument would find no outstanding code and report
     * {@code auth.otp.noActive}: a user who registered as {@code Ali@x.com} and typed
     * {@code ali@x.com} into the verification form would be told their code had expired.
     *
     * <p>A wrong code is counted. Once {@code otp.max-attempts} failures accumulate against the
     * same code it is marked used, so guessing must start over from a newly emailed code rather
     * than continuing against the one already in flight. Without this, a six-digit code with a
     * ten-minute lifetime and no attempt ceiling is enumerable.
     */
    public Otp validateCode(String email, String code, OtpType type) {
        // The anonymous reset flow answers every failure the same way. Reached without a session,
        // it would otherwise re-derive in one request what forgot-password had just stopped
        // disclosing: "no outstanding code" is only ever true for an address with no account or no
        // pending reset, while "wrong code" proves both exist. The distinction is worth nothing to
        // the person who asked for the code -- they do the same thing either way, which is ask for
        // another one -- and it is the whole answer to somebody testing an address.
        //
        // Email verification is not anonymous in the same sense. The caller has just registered and
        // already knows the account exists, because they created it, so the specific wording stays.
        boolean anonymous = type == OtpType.PASSWORD_RESET;

        var otp = otpRepository
                .findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                        EmailAddress.canonical(email), type)
                .orElseThrow(() -> new BusinessException(
                        anonymous ? "auth.otp.invalidOrExpired" : "auth.otp.noActive"));

        if (otp.isExpired()) {
            throw new BusinessException(
                    anonymous ? "auth.otp.invalidOrExpired" : "auth.otp.expired");
        }

        if (!otp.getCode().equals(code)) {
            // Committed in its own transaction, because throwing below rolls this one back and
            // would otherwise discard the increment — leaving the attacker unlimited guesses.
            int attempts = attemptRecorder.recordFailure(otp.getId(), maxAttempts);
            if (attempts >= maxAttempts) {
                // The ceiling is still enforced -- this code is spent either way. It is only the
                // announcement that is withheld, because "you have used up your attempts" is itself
                // a statement that there were attempts to use up, and so that the account is real.
                throw new BusinessException(
                        anonymous ? "auth.otp.invalidOrExpired" : "auth.otp.tooManyAttempts");
            }
            throw new BusinessException(
                    anonymous ? "auth.otp.invalidOrExpired" : "auth.otp.invalid");
        }

        return otp;
    }

    /**
     * Consumes a code, once.
     *
     * <p>The check and the spend are one statement. Previously this loaded the row, set
     * {@code used} on the loaded copy and saved it, which meant several requests carrying the same
     * correct code could each read it while it was still unused and each go on to succeed — a
     * one-time code accepted as many times as there were requests in flight. The conditional update
     * gives exactly one of them the row.
     *
     * <p>It also no longer writes the whole entity back. That full-column UPDATE rewrote
     * {@code attempts} from the value loaded at the start of the transaction, so a consume could
     * silently roll back failure counts committed alongside it.
     */
    @Transactional
    public void verify(String email, String code, OtpType type) {
        var otp = validateCode(email, code, type);

        if (otpRepository.consume(otp.getId()) == 0) {
            // Another request spent this code between the read above and here. From the caller's
            // side that is indistinguishable from submitting an already-used code, which is what it
            // is, and it is answered the same way.
            throw new BusinessException("auth.otp.invalid");
        }
    }
}
