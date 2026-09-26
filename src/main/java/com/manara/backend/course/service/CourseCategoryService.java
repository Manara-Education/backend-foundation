package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseCategoryResponse;
import com.manara.backend.course.repository.CourseCategoryRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** The categories offered to instructors in the course editor, in display order. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseCategoryService {

    private final CourseCategoryRepository courseCategoryRepository;

    public List<CourseCategoryResponse> listForInstructor(User user) {
        if (user == null || user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        return courseCategoryRepository.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .map(category -> new CourseCategoryResponse(category.getId(), category.getNameAr(), category.getColorToken()))
                .toList();
    }
}
