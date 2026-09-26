package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseCategory;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CourseCategoryRepository extends JpaRepository<@NonNull CourseCategory, @NonNull Long> {

    List<CourseCategory> findByActiveTrueOrderBySortOrderAscIdAsc();

    /** A category a course may be put in: it exists and is still offered. */
    Optional<CourseCategory> findByIdAndActiveTrue(Long id);
}
