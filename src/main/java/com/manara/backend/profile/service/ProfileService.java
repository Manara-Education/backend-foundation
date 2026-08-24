package com.manara.backend.profile.service;

import com.manara.backend.common.dto.MessageResponse;
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

    public ProfileResponse getProfile(User user) {
        return profileMapper.toProfileResponse(user);
    }

    @Transactional
    public MessageResponse updateProfile(User user, UpdateProfileRequest request) {
        user.setFullName(request.getFullName());
        userRepository.save(user);
        return MessageResponse.builder()
                .message(messageService.get("profile.update.success"))
                .build();
    }
}
