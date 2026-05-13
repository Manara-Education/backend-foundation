package com.manara.backend.profile.service;

import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.profile.dto.InstructorPublicResponse;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InstructorService {

    private final InstructorRepository instructorRepository;
    private final CourseRepository courseRepository;
    private final CourseMapper courseMapper;

    public InstructorPublicResponse getInstructorProfile(Long instructorId) {
        Instructor instructor = instructorRepository.findById(instructorId)
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", instructorId.toString()));

        return InstructorPublicResponse.builder()
                .id(instructor.getId())
                .fullName(instructor.getUser().getFullName())
                .bio(instructor.getBio())
                .specialization(instructor.getSpecialization())
                .build();
    }

    public List<CourseResponse> getInstructorCourses(Long instructorId) {
        // Verify instructor exists
        if (!instructorRepository.existsById(instructorId)) {
            throw new ResourceNotFoundException("error.profile.instructorNotFound", instructorId.toString());
        }

        return courseRepository.findByInstructorId(instructorId).stream()
                .map(courseMapper::toCourseResponse)
                .collect(Collectors.toList());
    }
}
