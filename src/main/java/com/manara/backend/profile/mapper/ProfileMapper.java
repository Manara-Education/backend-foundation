package com.manara.backend.profile.mapper;

import com.manara.backend.profile.dto.ProfileResponse;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.user.model.User;
import org.springframework.stereotype.Component;

@Component
public class ProfileMapper {

    public Instructor toInstructor(User user) {
        return Instructor.builder().user(user).build();
    }

    public Student toStudent(User user) {
        return Student.builder().user(user).build();
    }

    public ProfileResponse toProfileResponse(User user) {
        return ProfileResponse.builder()
                .fullName(user.getFullName())
                .email(user.getEmail())
                .role(user.getRole().name())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
