package com.manara.backend.auth.service;

import com.manara.backend.auth.email.OtpEmailFactory;
import com.manara.backend.auth.mapper.OtpMapper;
import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import com.manara.backend.email.exception.EmailDeliveryException;
import com.manara.backend.email.model.EmailMessage;
import com.manara.backend.email.model.EmailSendResult;
import com.manara.backend.email.service.EmailService;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * The OTP service is tested against {@link EmailService}, never against Resend. That boundary is
 * the point of the design: the OTP feature has no idea which provider delivers its mail.
 */
@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    private static final int EXPIRATION_MINUTES = 10;
    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private OtpRepository otpRepository;

    @Mock
    private OtpMapper otpMapper;

    @Mock
    private OtpEmailFactory otpEmailFactory;

    @Mock
    private EmailService emailService;

    @Mock
    private OtpAttemptRecorder attemptRecorder;

    @Captor
    private ArgumentCaptor<String> codeCaptor;

    private OtpService otpService;

    private final User user = User.builder().id(7L).email("student@manara.com").build();

    @BeforeEach
    void setUp() {
        otpService = new OtpService(otpRepository, otpMapper, new SecureRandom(),
                otpEmailFactory, emailService, attemptRecorder);
        ReflectionTestUtils.setField(otpService, "expirationMinutes", EXPIRATION_MINUTES);
        ReflectionTestUtils.setField(otpService, "maxAttempts", MAX_ATTEMPTS);
    }

    @Test
    void invalidatesPreviousCodesPersistsANewOneAndEmailsIt() {
        EmailMessage message = EmailMessage.builder()
                .to("student@manara.com").subject("s").html("<p>h</p>").build();
        given(otpEmailFactory.create(any(), any(), any(), anyInt())).willReturn(message);
        given(emailService.send(message)).willReturn(new EmailSendResult("msg-1"));
        given(otpMapper.toOtp(any(), any(), any(), any())).willReturn(new Otp());

        otpService.generateAndSend(user, OtpType.EMAIL_VERIFICATION);

        verify(otpRepository).invalidateAllByUserIdAndType(7L, OtpType.EMAIL_VERIFICATION);
        verify(otpRepository).save(any(Otp.class));
        verify(emailService).send(message);
        verify(otpEmailFactory).create(eq("student@manara.com"), codeCaptor.capture(),
                eq(OtpType.EMAIL_VERIFICATION), eq(EXPIRATION_MINUTES));
        assertThat(codeCaptor.getValue()).matches("\\d{6}");
    }

    @Test
    void persistsTheSameCodeThatIsEmailed() {
        EmailMessage message = EmailMessage.builder()
                .to("student@manara.com").subject("s").html("<p>h</p>").build();
        given(otpEmailFactory.create(any(), any(), any(), anyInt())).willReturn(message);
        given(otpMapper.toOtp(any(), any(), any(), any())).willReturn(new Otp());

        otpService.generateAndSend(user, OtpType.PASSWORD_RESET);

        ArgumentCaptor<String> persisted = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> expiry = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(otpMapper).toOtp(eq(user), persisted.capture(), eq(OtpType.PASSWORD_RESET),
                expiry.capture());

        ArgumentCaptor<String> emailed = ArgumentCaptor.forClass(String.class);
        verify(otpEmailFactory).create(eq("student@manara.com"), emailed.capture(),
                eq(OtpType.PASSWORD_RESET), eq(EXPIRATION_MINUTES));

        assertThat(persisted.getValue()).isEqualTo(emailed.getValue());
        assertThat(expiry.getValue()).isAfter(LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES - 1));
    }

    /**
     * Delivery failure must reach the caller so the surrounding transaction rolls back rather than
     * leaving a user with an account and no way to verify it.
     */
    @Test
    void propagatesDeliveryFailures() {
        given(otpEmailFactory.create(any(), any(), any(), anyInt())).willReturn(
                EmailMessage.builder().to("student@manara.com").subject("s").html("<p>h</p>").build());
        given(otpMapper.toOtp(any(), any(), any(), any())).willReturn(new Otp());
        given(emailService.send(any())).willThrow(new EmailDeliveryException("error.email.deliveryFailed"));

        assertThatThrownBy(() -> otpService.generateAndSend(user, OtpType.EMAIL_VERIFICATION))
                .isInstanceOf(EmailDeliveryException.class);
    }

    /** The generated code is never handed back to callers — only the email carries it. */
    @Test
    void returnsNothingToCallers() throws Exception {
        assertThat(OtpService.class.getMethod("generateAndSend", User.class, OtpType.class)
                .getReturnType()).isEqualTo(void.class);
    }

    // ── Attempt limiting ──────────────────────────────────────────────────────
    // A six-digit code has a million possibilities and a ten-minute life. Without a ceiling on
    // guesses that is not a secret, and these codes gate both registration and password reset.

    @Test
    void aWrongCodeIsCountedAgainstTheAllowance() {
        given(otpRepository.findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                "student@manara.com", OtpType.EMAIL_VERIFICATION))
                .willReturn(java.util.Optional.of(activeCode("123456")));
        given(attemptRecorder.recordFailure(99L, MAX_ATTEMPTS)).willReturn(1);

        assertThatThrownBy(() -> otpService.validateCode(
                "student@manara.com", "000000", OtpType.EMAIL_VERIFICATION))
                .hasMessage("auth.otp.invalid");

        verify(attemptRecorder).recordFailure(99L, MAX_ATTEMPTS);
    }

    @Test
    void exhaustingTheAllowanceReportsTooManyAttemptsRatherThanAnotherInvalidCode() {
        given(otpRepository.findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                "student@manara.com", OtpType.EMAIL_VERIFICATION))
                .willReturn(java.util.Optional.of(activeCode("123456")));
        given(attemptRecorder.recordFailure(99L, MAX_ATTEMPTS)).willReturn(MAX_ATTEMPTS);

        assertThatThrownBy(() -> otpService.validateCode(
                "student@manara.com", "000000", OtpType.EMAIL_VERIFICATION))
                .hasMessage("auth.otp.tooManyAttempts");
    }

    @Test
    void aCorrectCodeIsAcceptedAndCostsNoAttempt() {
        Otp otp = activeCode("123456");
        given(otpRepository.findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                "student@manara.com", OtpType.EMAIL_VERIFICATION))
                .willReturn(java.util.Optional.of(otp));

        assertThat(otpService.validateCode(
                "student@manara.com", "123456", OtpType.EMAIL_VERIFICATION)).isSameAs(otp);

        verify(attemptRecorder, org.mockito.Mockito.never())
                .recordFailure(org.mockito.ArgumentMatchers.anyLong(), anyInt());
    }

    /** An expired code is rejected before any attempt is spent — it is already worthless. */
    @Test
    void anExpiredCodeIsRejectedWithoutSpendingAnAttempt() {
        Otp expired = activeCode("123456");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        given(otpRepository.findTopByUserEmailAndTypeAndUsedFalseOrderByCreatedAtDesc(
                "student@manara.com", OtpType.EMAIL_VERIFICATION))
                .willReturn(java.util.Optional.of(expired));

        assertThatThrownBy(() -> otpService.validateCode(
                "student@manara.com", "000000", OtpType.EMAIL_VERIFICATION))
                .hasMessage("auth.otp.expired");

        verify(attemptRecorder, org.mockito.Mockito.never())
                .recordFailure(org.mockito.ArgumentMatchers.anyLong(), anyInt());
    }

    private Otp activeCode(String code) {
        return Otp.builder()
                .id(99L)
                .code(code)
                .type(OtpType.EMAIL_VERIFICATION)
                .used(false)
                .attempts(0)
                .expiresAt(LocalDateTime.now().plusMinutes(EXPIRATION_MINUTES))
                .user(user)
                .build();
    }
}
