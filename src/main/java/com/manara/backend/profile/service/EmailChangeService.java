package com.manara.backend.profile.service;

import com.manara.backend.auth.email.OtpEmailFactory;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ConflictException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.exception.TooManyRequestsException;
import com.manara.backend.common.util.EmailAddress;
import com.manara.backend.email.service.DeferredEmailDispatcher;
import com.manara.backend.profile.config.EmailChangeProperties;
import com.manara.backend.profile.dto.EmailChangeChallengeResponse;
import com.manara.backend.profile.dto.EmailChangeStartRequest;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.email.EmailChangedNoticeFactory;
import com.manara.backend.profile.mapper.EmailChangeMapper;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.profile.model.EmailChangeRequest;
import com.manara.backend.profile.model.EmailChangeStatus;
import com.manara.backend.profile.repository.EmailChangeRequestRepository;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Moving a signed-in account to a new email address, proven by a code sent to that address.
 *
 * <h2>What the responses never say</h2>
 * Whether the new address belongs to another account. Starting a change to a taken address is
 * answered exactly like any other start, and a request row is written for it — but no code is ever
 * sent, so it can never complete. Only at verification, which needs a code only the mailbox's owner
 * has, can a neutral "unavailable" appear, and then only for an address taken since.
 *
 * <h2>What makes a change final</h2>
 * One transaction: the request is spent by a conditional update (so two verifications cannot both
 * succeed), the account row is re-read under lock, the address and its verified flag are written and
 * flushed against the database's unique index, and the account's authentication epoch is bumped so
 * every other session ends. The caller is then given a fresh session. The notice to the previous
 * address is dispatched after commit: its failure cannot undo the change, and nothing is sent for a
 * change that rolled back.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EmailChangeService {

    private final EmailChangeRequestRepository requestRepository;
    private final UserRepository userRepository;
    private final EmailChangeMapper emailChangeMapper;
    private final ProfileMapper profileMapper;
    private final EmailChangeCodeHasher codeHasher;
    private final EmailChangeAttemptRecorder attemptRecorder;
    private final EmailChangeProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final OtpEmailFactory otpEmailFactory;
    private final EmailChangedNoticeFactory noticeFactory;
    private final DeferredEmailDispatcher emailDispatcher;
    private final SessionManager sessionManager;
    private final SecureRandom secureRandom;
    private final Clock clock;

    @Transactional
    public EmailChangeChallengeResponse start(User principal, EmailChangeStartRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        // Locked first, so two starts from one account are serialised: the second sees the first's
        // row, supersedes it or waits out its cooldown, and the one-pending index is never raced.
        User account = lockedAccount(principal.getId());

        if (!passwordEncoder.matches(request.getCurrentPassword(), account.getPassword())) {
            throw new BusinessException(ErrorCode.EMAIL_CHANGE_PASSWORD_INVALID, "profile.email.passwordInvalid");
        }

        String newEmail = EmailAddress.canonical(request.getNewEmail());
        if (newEmail.equals(account.getEmail())) {
            throw new BusinessException(ErrorCode.EMAIL_CHANGE_SAME_ADDRESS, "profile.email.sameAsCurrent");
        }

        requestRepository.findTopByUserIdOrderByCreatedAtDesc(account.getId())
                .filter(latest -> now.isBefore(latest.getResendAvailableAt()))
                .ifPresent(latest -> {
                    throw cooldown(Duration.between(now, latest.getResendAvailableAt()));
                });

        requestRepository.supersedePending(account.getId(), EmailChangeStatus.PENDING, EmailChangeStatus.SUPERSEDED);

        boolean targetUnavailable = userRepository.existsByEmail(newEmail);
        UUID requestId = UUID.randomUUID();
        String code = newCode();
        EmailChangeRequest saved = requestRepository.save(emailChangeMapper.toEntity(
                account, requestId, newEmail, codeHasher.hash(requestId, 0, code), targetUnavailable, now,
                now.plus(properties.codeValidity()), now.plus(properties.resendCooldown())));

        sendCode(saved, code);
        return emailChangeMapper.toResponse(saved, now);
    }

    @Transactional
    public EmailChangeChallengeResponse resend(User principal, UUID requestId) {
        LocalDateTime now = LocalDateTime.now(clock);
        EmailChangeRequest request = ownPendingRequest(principal, requestId, now);

        if (now.isBefore(request.getResendAvailableAt())) {
            throw cooldown(Duration.between(now, request.getResendAvailableAt()));
        }
        if (request.getResendCount() >= properties.maxResends()) {
            throw new TooManyRequestsException(ErrorCode.EMAIL_CHANGE_LOCKED, Duration.ZERO,
                    "profile.email.tooManyResends");
        }

        // A new generation: every earlier code for this request stops matching. Wrong attempts are
        // not reset, and the request never lives past its absolute lifetime.
        int generation = request.getResendCount() + 1;
        String code = newCode();
        LocalDateTime lifetimeEnd = request.getCreatedAt().plus(properties.maxLifetime());
        LocalDateTime expiresAt = now.plus(properties.codeValidity());
        request.setResendCount(generation);
        request.setCodeHash(codeHasher.hash(request.getRequestId(), generation, code));
        request.setExpiresAt(expiresAt.isAfter(lifetimeEnd) ? lifetimeEnd : expiresAt);
        request.setResendAvailableAt(now.plus(properties.resendCooldown()));

        sendCode(request, code);
        return emailChangeMapper.toResponse(request, now);
    }

    @Transactional(noRollbackFor = TooManyRequestsException.class)
    public ProfileResponse verify(User principal, UUID requestId, String code,
                                  HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        LocalDateTime now = LocalDateTime.now(clock);
        EmailChangeRequest request = ownPendingRequest(principal, requestId, now);

        if (!codeHasher.matches(request.getRequestId(), request.getResendCount(), code, request.getCodeHash())) {
            int attempts = attemptRecorder.recordFailure(request.getId(), properties.maxAttempts());
            if (attempts >= properties.maxAttempts()) {
                throw new TooManyRequestsException(ErrorCode.EMAIL_CHANGE_LOCKED, Duration.ZERO, "profile.email.locked");
            }
            throw new BusinessException(ErrorCode.EMAIL_CHANGE_CODE_INVALID, "profile.email.codeInvalid");
        }

        if (requestRepository.consume(request.getId(), now, properties.maxAttempts()) == 0) {
            // Spent, replaced, expired or locked between the read above and here.
            throw new BusinessException(ErrorCode.EMAIL_CHANGE_CODE_INVALID, "profile.email.codeInvalid");
        }

        User account = lockedAccount(principal.getId());
        String previousEmail = account.getEmail();
        String newEmail = request.getNewEmail();

        boolean takenByAnother = userRepository.findByEmail(newEmail)
                .filter(owner -> !owner.getId().equals(account.getId()))
                .isPresent();
        if (takenByAnother) {
            throw unavailable();
        }

        account.setEmail(newEmail);
        account.setEmailVerified(true);
        try {
            // Flushed here rather than at commit, so a registration that took the address a moment
            // ago meets the unique index inside this method and is answered neutrally.
            userRepository.saveAndFlush(account);
        } catch (DataIntegrityViolationException lostRace) {
            throw unavailable();
        }
        userRepository.bumpAuthVersion(account.getId());

        emailDispatcher.dispatchAfterCommit(noticeFactory.create(previousEmail));

        User current = userRepository.findById(account.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound"));
        sessionManager.establish(current, httpRequest, httpResponse);
        log.info("Email address changed for userId={}", current.getId());
        return profileMapper.toProfileResponse(current);
    }

    // --- shared steps ---------------------------------------------------------

    /**
     * The caller's own request, still usable. Another account's id and an id that never existed are
     * the same answer, so request ids cannot be probed.
     */
    private EmailChangeRequest ownPendingRequest(User principal, UUID requestId, LocalDateTime now) {
        EmailChangeRequest request = requestRepository.findByRequestIdAndUserId(requestId, principal.getId())
                .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_CHANGE_CODE_INVALID, "profile.email.codeInvalid"));
        return switch (request.getStatus()) {
            case LOCKED -> throw new TooManyRequestsException(ErrorCode.EMAIL_CHANGE_LOCKED, Duration.ZERO, "profile.email.locked");
            case SUPERSEDED -> throw new BusinessException(ErrorCode.EMAIL_CHANGE_EXPIRED, "profile.email.expired");
            case CONSUMED -> throw new BusinessException(ErrorCode.EMAIL_CHANGE_CODE_INVALID, "profile.email.codeInvalid");
            case PENDING -> {
                if (!now.isBefore(request.getExpiresAt())) {
                    throw new BusinessException(ErrorCode.EMAIL_CHANGE_EXPIRED, "profile.email.expired");
                }
                yield request;
            }
        };
    }

    /** Sent after commit, and never to an address that belonged to another account at request time. */
    private void sendCode(EmailChangeRequest request, String code) {
        if (request.isTargetUnavailable()) {
            return;
        }
        int minutes = (int) Math.max(1, Duration.between(LocalDateTime.now(clock), request.getExpiresAt()).toMinutes());
        emailDispatcher.dispatchAfterCommit(otpEmailFactory.createEmailChange(request.getNewEmail(), code, minutes));
    }

    private String newCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private User lockedAccount(Long userId) {
        return userRepository.findForUpdateById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound"));
    }

    private TooManyRequestsException cooldown(Duration remaining) {
        return new TooManyRequestsException(ErrorCode.EMAIL_CHANGE_COOLDOWN, remaining, "profile.email.cooldown");
    }

    private ConflictException unavailable() {
        return new ConflictException(ErrorCode.EMAIL_CHANGE_UNAVAILABLE, "profile.email.unavailable");
    }
}
