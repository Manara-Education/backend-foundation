package com.manara.backend.auth.service;

import com.manara.backend.auth.dto.*;
import com.manara.backend.auth.mapper.AuthMapper;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.session.manager.SessionManager;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.service.MessageService;
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

    @Transactional
    public MessageResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("auth.email.duplicate");
        }

        var roleToSet = request.getRole() != null ? request.getRole() : Role.STUDENT;
        var encodedPassword = passwordEncoder.encode(request.getPassword());
        var user = userRepository.save(authMapper.toUser(request, encodedPassword, roleToSet));

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

    /**
     * Sends another code, if there is an account to send one to.
     *
     * <p>Answers the same way whatever is true of the address. This endpoint used to be a three-way
     * oracle for anyone who could reach it: 404 said no account existed, 400 "email is already
     * verified" said one existed and was confirmed, and 200 said one existed and was not. An
     * unauthenticated caller could sort any list of addresses into those three buckets.
     *
     * <p>So the work is now conditional and the answer is not. An absent account does nothing; an
     * already-verified account asking for a verification code does nothing, because there is nothing
     * it needs; anything else gets a code. All three return the same status, envelope and text.
     */
    @Transactional
    public MessageResponse resendOtp(ResendOtpRequest request) {
        OtpType typeToResend = request.getType() != null ? request.getType() : OtpType.EMAIL_VERIFICATION;

        userRepository.findByEmail(request.getEmail())
                .filter(user -> typeToResend != OtpType.EMAIL_VERIFICATION || !user.isEmailVerified())
                .ifPresent(user -> otpService.generateAndSendQuietly(user, typeToResend));

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.sentIfAccountExists"))
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

    /**
     * Starts password recovery, if there is an account to start it for.
     *
     * <p>An address with no account used to be answered 404, with the submitted address quoted back
     * in the message, while an address with one was answered 200 and sent a code. That is a
     * membership test on the platform, available to anybody, one request at a time.
     *
     * <p>Now both are answered identically. The code is still only generated for an account that
     * exists, and it still only goes to that account's own address -- nothing is sent anywhere on
     * behalf of an address that has no account.
     */
    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        userRepository.findByEmail(request.getEmail())
                .ifPresent(user -> otpService.generateAndSendQuietly(user, OtpType.PASSWORD_RESET));

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.sentIfAccountExists"))
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
     * Changes the password of the signed-in account, and with it clears any forced-reset flag.
     *
     * Separate from {@link #resetPassword} on purpose: that one serves the anonymous
     * forgot-password flow and proves identity with an emailed OTP. This one serves a caller who
     * is already authenticated and knows the current password -- the case where an operator has
     * required the account to move off a provisioned or compromised password.
     *
     * The hash and the flag are written together, in this one transaction. If the new password
     * is rejected, nothing is persisted and the account still owes the change.
     */
    @Transactional
    public MessageResponse changePassword(User principal, ChangePasswordRequest request) {
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

        return MessageResponse.builder()
                .message(messageService.get("auth.password.changeSuccess"))
                .build();
    }

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

        return MessageResponse.builder()
                .message(messageService.get("auth.password.resetSuccess"))
                .build();
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFoundByEmail", email));
    }
}