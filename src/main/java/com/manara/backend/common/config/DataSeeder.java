package com.manara.backend.common.config;

import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final InstructorRepository instructorRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        String defaultInstructorEmail = "instructor@manara.com";

        if (userRepository.findByEmail(defaultInstructorEmail).isEmpty()) {
            log.info("Seeding default instructor: {}", defaultInstructorEmail);

            User instructorUser = User.builder()
                    .fullName("Mohamed Hamed")
                    .email(defaultInstructorEmail)
                    .password(passwordEncoder.encode("password123"))
                    .role(Role.INSTRUCTOR)
                    .emailVerified(true)
                    .build();

            instructorUser = userRepository.save(instructorUser);

            Instructor instructor = Instructor.builder()
                    .user(instructorUser)
                    .bio("I am a hardcoded instructor")
                    .specialization("Software Engineering")
                    .build();

            instructorRepository.save(instructor);

            log.info("Default instructor seeded successfully.");
        } else {
            log.info("Default instructor already exists. Skipping seed.");
        }
    }
}
