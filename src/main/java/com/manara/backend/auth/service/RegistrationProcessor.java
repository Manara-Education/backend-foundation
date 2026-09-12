package com.manara.backend.auth.service;

import com.manara.backend.auth.dto.RegisterRequest;
import com.manara.backend.auth.mapper.AuthMapper;
import com.manara.backend.auth.model.OtpType;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.terms.model.TermsVersion;
import com.manara.backend.terms.service.TermsService;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of registration, and the part {@link AuthService#register} has to be able
 * to call again in a fresh transaction.
 *
 * <p>Split out for the reason {@code CheckoutProcessor} is. When two registrations race for one new
 * address, the unique index on {@code users.email} refuses the loser's insert, and the
 * {@code DataIntegrityViolationException} leaves the transaction it happened in unusable. Answering
 * the loser therefore needs a new transaction, which a self-call inside one bean would bypass the
 * proxy and never get.
 */
@Service
@RequiredArgsConstructor
public class RegistrationProcessor {

    private final UserRepository userRepository;
    private final InstructorRepository instructorRepository;
    private final StudentRepository studentRepository;
    private final AuthMapper authMapper;
    private final ProfileMapper profileMapper;
    private final TermsService termsService;
    private final OtpService otpService;
    private final AccountExistsNotifier accountExistsNotifier;

    /**
     * Creates the account, its consent row, its profile and its first code. If the address already
     * has an account, nothing on that account is touched and its owner is told instead.
     *
     * <p>Both outcomes return nothing and hand their email to the dispatcher for after commit, so
     * neither the caller nor the person it answers can tell them apart by what comes back or by
     * waiting on the provider.
     */
    @Transactional
    public void register(RegisterRequest request, String encodedPassword, Role role,
                         TermsVersion acceptedTermsVersion) {
        if (userRepository.existsByEmail(request.getEmail())) {
            accountExistsNotifier.notifyOwner(request.getEmail());
            return;
        }

        var user = userRepository.save(authMapper.toUser(request, encodedPassword, role));

        termsService.recordAcceptance(user, acceptedTermsVersion);

        if (role == Role.INSTRUCTOR) {
            instructorRepository.save(profileMapper.toInstructor(user));
        } else if (role == Role.STUDENT) {
            studentRepository.save(profileMapper.toStudent(user));
        }

        otpService.generateAndSendQuietly(user, OtpType.EMAIL_VERIFICATION);
    }

    /**
     * Answers a registration whose insert the database refused.
     *
     * @return whether an account now holds the address. If one does, the registration lost a race
     *         and is the existing-account case, and the owner has been told as any owner would be. If
     *         none does, the refusal was about something else and the caller must not hide it.
     */
    @Transactional
    public boolean settleLostRace(String email) {
        if (!userRepository.existsByEmail(email)) {
            return false;
        }
        accountExistsNotifier.notifyOwner(email);
        return true;
    }
}
