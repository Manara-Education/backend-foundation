package com.manara.backend.auth.service;

import com.manara.backend.auth.model.Otp;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.auth.repository.OtpRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OtpAttemptRecorderTest {

    private static final int MAX_ATTEMPTS = 5;

    @Mock
    private OtpRepository otpRepository;

    @InjectMocks
    private OtpAttemptRecorder recorder;

    @Test
    void countsAFailureAndLeavesTheCodeUsableWhileAllowanceRemains() {
        Otp otp = code(0);
        given(otpRepository.findById(1L)).willReturn(Optional.of(otp));

        assertThat(recorder.recordFailure(1L, MAX_ATTEMPTS)).isEqualTo(1);

        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().getAttempts()).isEqualTo(1);
        assertThat(saved.getValue().isUsed()).isFalse();
    }

    /**
     * The final failure burns the code. Leaving it usable would mean the ceiling only slowed an
     * attacker down rather than stopping them — they could keep guessing the same live code.
     */
    @Test
    void theFinalFailureMarksTheCodeUsedSoItCannotBeGuessedFurther() {
        Otp otp = code(MAX_ATTEMPTS - 1);
        given(otpRepository.findById(1L)).willReturn(Optional.of(otp));

        assertThat(recorder.recordFailure(1L, MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS);

        ArgumentCaptor<Otp> saved = ArgumentCaptor.forClass(Otp.class);
        verify(otpRepository).save(saved.capture());
        assertThat(saved.getValue().isUsed()).isTrue();
    }

    /** A code that has vanished between lookup and record is treated as exhausted, not as free. */
    @Test
    void aMissingCodeIsTreatedAsExhausted() {
        given(otpRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(recorder.recordFailure(1L, MAX_ATTEMPTS)).isEqualTo(MAX_ATTEMPTS);
    }

    private Otp code(int attempts) {
        return Otp.builder()
                .id(1L)
                .code("123456")
                .type(OtpType.EMAIL_VERIFICATION)
                .used(false)
                .attempts(attempts)
                .expiresAt(LocalDateTime.now().plusMinutes(10))
                .build();
    }
}
