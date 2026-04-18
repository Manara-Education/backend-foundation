package com.manara.backend.user.repository;

import com.manara.backend.user.model.User;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<@NonNull User, @NonNull Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
