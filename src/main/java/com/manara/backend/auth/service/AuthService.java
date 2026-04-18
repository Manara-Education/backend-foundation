package com.manara.backend.auth.service;

import com.manara.backend.auth.dto.*;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final AuthenticationManager authenticationManager;
    private final MessageService messageService;

    @Transactional
    public MessageResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new BusinessException("auth.email.duplicate");
        }

        var user = User.builder()
                .fullName(request.getFullName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .build();

        user = userRepository.save(user);
        otpService.generateAndSave(user, OtpType.EMAIL_VERIFICATION);

        return MessageResponse.builder()
                .message(messageService.get("auth.register.success"))
                .build();
    }

    @Transactional
    public AuthResponse verifyOtp(OtpVerifyRequest request) {
        otpService.verify(request.getEmail(), request.getCode(), OtpType.EMAIL_VERIFICATION);

        var user = findUserByEmail(request.getEmail());
        user.setEmailVerified(true);
        userRepository.save(user);

        return buildAuthResponse(user);
    }

    @Transactional
    public MessageResponse resendOtp(ResendOtpRequest request) {
        var user = findUserByEmail(request.getEmail());

        if (user.isEmailVerified()) {
            throw new BusinessException("auth.email.alreadyVerified");
        }

        otpService.generateAndSave(user, OtpType.EMAIL_VERIFICATION);

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.resent"))
                .build();
    }

    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()));

        var user = findUserByEmail(request.getEmail());

        if (!user.isEmailVerified()) {
            throw new BusinessException("auth.email.notVerified");
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public MessageResponse forgotPassword(ForgotPasswordRequest request) {
        var user = findUserByEmail(request.getEmail());
        otpService.generateAndSave(user, OtpType.PASSWORD_RESET);

        return MessageResponse.builder()
                .message(messageService.get("auth.otp.sentForReset"))
                .build();
    }

    @Transactional
    public MessageResponse resetPassword(ResetPasswordRequest request) {
        otpService.verify(request.getEmail(), request.getCode(), OtpType.PASSWORD_RESET);

        var user = findUserByEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        return MessageResponse.builder()
                .message(messageService.get("auth.password.resetSuccess"))
                .build();
    }

    private User findUserByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFoundByEmail", email));
    }

    private AuthResponse buildAuthResponse(User user) {
        var token = jwtService.generateToken(user);
        return AuthResponse.builder()
                .token(token)
                .fullName(user.getFullName())
                .email(user.getEmail())
                .build();
    }
}
