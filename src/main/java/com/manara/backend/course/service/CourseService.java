package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.SharedData;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final InstructorRepository instructorRepository;
    private final StudentRepository studentRepository;

    public List<CourseResponse> getAllCourses() {
        return courseRepository.findAll().stream()
                .map(this::mapToCourseResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CourseResponse createCourse(User user, CourseRequest request) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }

        var instructor = instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", user.getId().toString()));

        var sharedData = SharedData.builder()
                .title(request.getTitle())
                .subtitle(request.getSubtitle())
                .image(request.getImage())
                .description(request.getDescription())
                .duration(request.getDuration())
                .build();

        var course = Course.builder()
                .sharedData(sharedData)
                .price(request.getPrice())
                .instructor(instructor)
                .build();

        course = courseRepository.save(course);
        return mapToCourseResponse(course);
    }

    @Transactional
    public EnrollmentResponse enrollInCourse(User user, Long courseId) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId()).isPresent()) {
            throw new BusinessException("error.course.alreadyEnrolled");
        }

        var enrollment = Enrollment.builder()
                .course(course)
                .student(student)
                .level("Beginner")
                .build();

        enrollmentRepository.save(enrollment);

        course.setStudentsCount(course.getStudentsCount() + 1);
        courseRepository.save(course);

        return mapToEnrollmentResponse(enrollment);
    }

    public List<EnrollmentResponse> getMyEnrollments(User user) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        return enrollmentRepository.findByStudentId(student.getId()).stream()
                .map(this::mapToEnrollmentResponse)
                .collect(Collectors.toList());
    }

    private CourseResponse mapToCourseResponse(Course course) {
        return CourseResponse.builder()
                .id(course.getId())
                .title(course.getSharedData().getTitle())
                .subtitle(course.getSharedData().getSubtitle())
                .image(course.getSharedData().getImage())
                .description(course.getSharedData().getDescription())
                .duration(course.getSharedData().getDuration())
                .price(course.getPrice())
                .studentsCount(course.getStudentsCount())
                .instructorName(course.getInstructor().getUser().getFullName())
                .createdAt(course.getCreatedAt())
                .build();
    }

    private EnrollmentResponse mapToEnrollmentResponse(Enrollment enrollment) {
        return EnrollmentResponse.builder()
                .id(enrollment.getId())
                .course(mapToCourseResponse(enrollment.getCourse()))
                .progress(enrollment.getProgress())
                .enrolled(enrollment.getEnrolled())
                .level(enrollment.getLevel())
                .enrolledAt(enrollment.getEnrolledAt())
                .build();
    }
}
