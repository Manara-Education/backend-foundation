package com.manara.backend.profile.service;

import com.manara.backend.auth.dto.MessageResponse;
import com.manara.backend.common.service.MessageService;
import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.dto.UpdateProfileRequest;
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

    public ProfileResponse getProfile(User user) {
        return buildProfileResponse(user);
    }

    @Transactional
    public MessageResponse updateProfile(User user, UpdateProfileRequest request) {
        user.setFullName(request.getFullName());
        userRepository.save(user);
        return MessageResponse.builder()
                .message(messageService.get("profile.update.success"))
                .build();
    }

    private ProfileResponse buildProfileResponse(User user) {
        return ProfileResponse.builder()
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
