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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final OtpService otpService;
    private final AuthenticationManager authenticationManager;
    private final MessageService messageService;
    private final SessionManager sessionManager;
    private final AuthMapper authMapper;
    private final TermsService termsService;
    private final RegistrationProcessor registrationProcessor;

    /**
     * The roles a stranger may give themselves by filling in the public registration form.
     *
     * <p>An allowlist rather than a check for ADMIN, so that the answer to "may the public assign
     * this role?" is no by default. A role added to {@link Role} later is refused here until
     * someone decides otherwise, which is the opposite of what a blacklist would do.
     *
     * <p>INSTRUCTOR is on the list because today it is the only way an instructor account comes
     * into existence — there is no instructor sign-up screen, no provisioning endpoint and no
     * seeder. Removing it here would close the sole onboarding path in the name of fixing a
     * different problem. Whether instructors ought to self-register is a product question, still
     * open; this method is only the place that stops ADMIN.
     */
    private static final Set<Role> SELF_ASSIGNABLE_ROLES = EnumSet.of(Role.STUDENT, Role.INSTRUCTOR);

    /**
     * Creates an account, or, for an address that already has one, answers exactly as if it had.
     *
     * <p>This method is the application's <strong>only</strong> account-creation path — there is no
     * social sign-up, no OAuth, no invitation flow, no admin-created account and no service-account
     * provisioning — which makes it the one place consent to the Terms and Conditions can be
     * required, and therefore the shared consent boundary. A future way to create a user that does
     * not come through here would be a way to create a user who never agreed to anything.
     *
     * <p>The terms check runs first, before the duplicate-address read and before anything at all is
     * written. A refused registration leaves no user row, no student or instructor profile, no
     * consent row, no OTP and no email — the account and its consent are created together in one
     * transaction, or neither is.
     *
     * <p>The role allowlist is checked immediately after, and still before the duplicate-address
     * read: a privileged registration is refused without writing a user, a profile or an OTP,
     * without sending mail, and without the reply revealing whether the address was already
     * registered.
     *
     * <p>Past those two checks the caller can no longer see which way it went. An address that
     * already had an account used to be refused with 400 "Email is already registered", which made
     * this form a membership test for anybody. Now a new address gets its account and a verification
     * code, while an existing one has nothing written to it and its owner gets a notice
     * ({@link AccountExistsNotifier}). Both are answered 201 with the same text. The work is kept
     * comparable as well as the answer: the password is hashed on both paths, and both emails go out
     * after commit and off the request thread, so neither path waits on the mail provider and an
     * outage no longer answers new addresses alone with a 503. Comparable is not constant — the new
     * path still writes rows the other does not.
     *
     * <p>A failed send therefore no longer rolls the registration back. The account stays,
     * unverified, and its owner asks for another code from the verification screen, as they would
     * after any lost email.
     *
     * <p>Not transactional itself, for the reason {@code CourseCheckoutService} is not. Two
     * registrations racing for one new address can both pass the existence check, and the unique
     * index on {@code users.email} refuses the second insert. That exception leaves its transaction
     * unusable, so the loser is answered from a fresh one, as the existing-account case it now is.
     */
    // NOT_SUPPORTED rather than nothing: the class default would otherwise open a read-only
    // transaction here, and the processor would join it -- read-only, and poisoned by a lost race.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public MessageResponse register(RegisterRequest request) {
        // Decided before any side effect. That the acceptance flag itself is an explicit `true` has
        // already been settled by validation on the request; what is checked here is that the
        // version accepted is the version in force.
        var acceptedTermsVersion = termsService.requireCurrentVersionAccepted(request.getTermsVersion());

        var roleToSet = request.getRole() != null ? request.getRole() : Role.STUDENT;

        // Before the duplicate-address check, not after it. A privileged request must be refused
        // without writing a user, a profile or an OTP, without sending mail — and without the
        // reply revealing whether the address was already registered, which is the very oracle
        // the duplicate check below would otherwise hand over as a side effect of this refusal.
        if (!SELF_ASSIGNABLE_ROLES.contains(roleToSet)) {
            throw new BusinessException("auth.role.notSelfAssignable");
        }

        // Hashed whether or not the address turns out to be taken, and before that is known. bcrypt is
        // deliberately the slowest thing this request does; skipping it for existing accounts had
        // them answered in about a twentieth of the time, which was the status code's disclosure again.
        var encodedPassword = passwordEncoder.encode(request.getPassword());

        try {
            registrationProcessor.register(request, encodedPassword, roleToSet, acceptedTermsVersion);
        } catch (DataIntegrityViolationException concurrentRegistration) {
            // Lost a race for a new address. The winner's row is committed by now, so this is the
            // existing-account case and is answered as one. If no account holds the address, the
            // database refused something else, and that is not this method's to hide.
            if (!registrationProcessor.settleLostRace(request.getEmail())) {
                throw concurrentRegistration;
            }
        }

        return MessageResponse.builder()
                .message(messageService.get("auth.register.accepted"))
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