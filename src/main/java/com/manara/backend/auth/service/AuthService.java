package com.manara.backend.auth.service;

import com.manara.backend.auth.dto.*;
import com.manara.backend.auth.mapper.AuthMapper;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.terms.service.TermsService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final InstructorRepository instructorRepository;
    private final StudentRepository studentRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final AuthenticationManager authenticationManager;
    private final MessageService messageService;
    private final SessionManager sessionManager;
    private final AuthMapper authMapper;
    private final ProfileMapper profileMapper;
    private final TermsService termsService;

    /**
     * Creates an account.
     *
     * <p>This method is the application's <strong>only</strong> account-creation path — there is no
     * social sign-up, no OAuth, no invitation flow, no admin-created account and no service-account
     * provisioning — which makes it the one place consent to the Terms and Conditions can be
     * required, and therefore the shared consent boundary. A future way to create a user that does
     * not come through here would be a way to create a user who never agreed to anything.
     *
     * <p>The terms check runs first, before the duplicate-address read and before anything at all is
     * written. A refused registration leaves no user row, no student or instructor profile, no
     * consent row, no OTP and no email — the account and its consent are created together in this
     * one transaction, or neither is.
     */
    @Transactional
    public MessageResponse register(RegisterRequest request) {
        // Decided before any side effect. That the acceptance flag itself is an explicit `true` has
        // already been settled by validation on the request; what is checked here is that the
        // version accepted is the version in force.
        var acceptedTermsVersion = termsService.requireCurrentVersionAccepted(request.getTermsVersion());

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("auth.email.duplicate");
        }

        var roleToSet = request.getRole() != null ? request.getRole() : Role.STUDENT;
        var encodedPassword = passwordEncoder.encode(request.getPassword());
        var user = userRepository.save(authMapper.toUser(request, encodedPassword, roleToSet));

        termsService.recordAcceptance(user, acceptedTermsVersion);

        if (roleToSet == Role.INSTRUCTOR) {
            instructorRepository.save(profileMapper.toInstructor(user));
        } else if (roleToSet == Role.STUDENT) {
            studentRepository.save(profileMapper.toStudent(user));
        }

        otpService.generateAndSend(user, OtpType.EMAIL_VERIFICATION);

        return MessageResponse.builder()
                .message(messageService.get("auth.register.success"))
                .build();
    }

    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest request,
                                  HttpServletRequest httpRequest,
                                  HttpServletResponse httpResponse) {
        otpService.verify(request.getEmail(), request.getCode(), OtpType.EMAIL_VERIFICATION);

        var user = findUserByEmail(request.getEmail());
        user.setEmailVerified(true);
        userRepository.save(user);

        sessionManager.establish(user, httpRequest, httpResponse);
        return authMapper.toAuthResponse(user);
    }

    @Transactional
    public MessageResponse resendOtp(ResendOtpRequest request) {
        var user = findUserByEmail(request.getEmail());

        OtpType typeToResend = request.getType() != null ? request.getType() : OtpType.EMAIL_VERIFICATION;

        if (typeToResend == OtpType.EMAIL_VERIFICATION && user.isEmailVerified()) {
            throw new BusinessException("auth.email.alreadyVerified");
        }

        otpService.generateAndSend(user, typeToResend);

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.resent"))
                .build();
    }

    public AuthResponse login(LoginRequest request,
                              HttpServletRequest httpRequest,
                              HttpServletResponse httpResponse) {
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()));

        var user = findUserByEmail(request.getEmail());

        if (!user.isEmailVerified()) {
            throw new BusinessException("auth.email.notVerified");
        }

        sessionManager.establish(auth, httpRequest, httpResponse);
        return authMapper.toAuthResponse(user);
    }

    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        var user = findUserByEmail(request.getEmail());
        otpService.generateAndSend(user, OtpType.PASSWORD_RESET);

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.sentForReset"))
                .build();
    }

    public MessageResponse verifyResetOtp(OtpVerifyRequest request) {
        otpService.validateCode(request.getEmail(), request.getCode(), OtpType.PASSWORD_RESET);

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.verified"))
                .build();
    }

    public MessageResponse logout(HttpServletRequest request, HttpServletResponse response) {
        sessionManager.terminate(request, response);
        return MessageResponse.builder()
                .message(messageService.get("auth.logout.success"))
                .build();
    }

    /**
     * The authoritative view of the signed-in account.
     *
     * Deliberately re-reads the row instead of mapping the principal the session handed over.
     * That principal was serialised when the session was established and never changes again,
     * so it would answer "reset required" forever -- including on the reload immediately after
     * the password was changed, which is exactly when the client asks.
     */
    public AuthResponse currentUser(User principal) {
        return authMapper.toAuthResponse(findUserByEmail(principal.getUsername()));
    }

    /**
     * Changes the password of the signed-in account, clears any forced-reset flag, and signs the
     * account's other devices out.
     *
     * Separate from {@link #resetPassword} on purpose: that one serves the anonymous
     * forgot-password flow and proves identity with an emailed OTP. This one serves a caller who
     * is already authenticated and knows the current password -- the case where an operator has
     * required the account to move off a provisioned or compromised password.
     *
     * The hash, the flag and the authentication epoch are written together, in this one
     * transaction. That atomicity is the point of doing it here rather than by deleting sessions
     * afterwards: a multi-key delete against the session store can half succeed, and a reset the
     * user was told had worked would then leave live sessions behind it. If the new password is
     * rejected, nothing is persisted, the account still owes the change, and no session is
     * disturbed.
     *
     * The caller keeps working, on a new session. It is issued only after the epoch has been
     * bumped and re-read, so it is stamped with the new value and not the one it replaced -- the
     * device that changed the password stays signed in and every other device for the account is
     * refused on its next request. Should this transaction roll back after that point, the caller
     * is signed out too rather than left holding a session on a superseded epoch, which is the
     * direction the failure has to fall.
     */
    @Transactional
    public MessageResponse changePassword(User principal,
                                          ChangePasswordRequest request,
                                          HttpServletRequest httpRequest,
                                          HttpServletResponse httpResponse) {
        var user = findUserByEmail(principal.getUsername());

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BusinessException("auth.password.currentInvalid");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPassword())) {
            throw new BusinessException("auth.password.sameAsCurrent");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setRequiresPasswordReset(false);
        userRepository.save(user);

        // Retires every session for this account, this one included.
        userRepository.bumpAuthVersion(user.getId());

        // Re-read, because the epoch the new session is stamped with has to be the one the database
        // now holds. The bump is a statement the database executes, so the instance above is not
        // updated by it -- stamping from that instance would mint a session on the old epoch and
        // sign the caller straight back out on their next request.
        sessionManager.establish(findUserByEmail(principal.getUsername()), httpRequest, httpResponse);

        return MessageResponse.builder()
                .message(messageService.get("auth.password.changeSuccess"))
                .build();
    }

    /**
     * The anonymous, emailed-code route to a new password. Ends every session on the account.
     *
     * Every session, with no exception for the caller, because there is no caller to except: this
     * flow is reached without one. Somebody who has just proved control of the mailbox is about to
     * sign in with the password they chose; anybody already signed in on this account at that
     * moment is either the same person on another device or the reason the password is being reset.
     * Both are shown the sign-in screen.
     */
    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        otpService.verify(request.getEmail(), request.getCode(), OtpType.PASSWORD_RESET);

        var user = findUserByEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        // Clears a forced-reset requirement too. The emailed code proves the account, and a
        // password just chosen through it is a password the account has moved off of -- leaving
        // the flag set here would strand the user: new password, still locked out.
        user.setRequiresPasswordReset(false);
        userRepository.save(user);

        // In the same transaction as the hash above. The old password stops working and the
        // sessions opened under it stop working at the same instant, or neither does.
        userRepository.bumpAuthVersion(user.getId());

        return MessageResponse.builder()
                .message(messageService.get("auth.password.resetSuccess"))
                .build();
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFoundByEmail", email));
    }
}