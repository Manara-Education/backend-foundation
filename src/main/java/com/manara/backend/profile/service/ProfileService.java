package com.manara.backend.profile.service;

import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.dto.UpdateProfileRequest;
import com.manara.backend.profile.mapper.ProfileMapper;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ProfileMapper profileMapper;

    public ProfileResponse getProfile(User principal) {
        return profileMapper.toProfileResponse(currentAccount(principal));
    }

    /**
     * Renames the signed-in account, and changes nothing else about it.
     *
     * <p>The argument is the principal the session handed over: an instance of the mapped entity,
     * serialised when the session was established and never updated since. It used to be given
     * straight to {@code save}, which — for an instance that already has an id — is a merge, and a
     * merge writes every updatable column from that snapshot. So a rename also wrote back the
     * snapshot's {@code password}, {@code role}, {@code requires_password_reset},
     * {@code email_verified} and {@code email}. A session older than a password reset could
     * therefore restore the previous hash, making the old password work again; a session older
     * than an operator's demotion could restore the previous role. The request only had to carry a
     * name.
     *
     * <p>So the row is re-read by id and the name set on <em>that</em> instance, inside this
     * transaction, leaving dirty checking to write the one column that actually changed. The same
     * re-read serves {@link #getProfile}, which otherwise answers from the sign-in snapshot and can
     * show a stale name straight after a successful rename.
     *
     * <p>Read-then-mutate rather than a {@code @Modifying} update on purpose: a bulk update
     * bypasses {@code @PreUpdate}, so {@code updated_at} would quietly stop advancing.
     */
    @Transactional
    public MessageResponse updateProfile(User principal, UpdateProfileRequest request) {
        currentAccount(principal).setFullName(request.getFullName());

        return MessageResponse.builder()
                .message(messageService.get("profile.update.success"))
                .build();
    }

    /**
     * The account as the database currently has it, never as the session remembers it.
     *
     * <p>Resolved by id: the principal's stable identifier is the one field of it that is safe to
     * believe, and the row it names is the authority on everything else.
     */
    private User currentAccount(User principal) {
        return userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.user.notFound"));
    }
}
