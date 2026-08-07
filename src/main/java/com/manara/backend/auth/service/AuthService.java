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
}