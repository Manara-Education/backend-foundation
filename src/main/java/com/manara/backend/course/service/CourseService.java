package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.file.FileUploadService;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.model.Student;
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
    private final CourseMapper courseMapper;
    private final FileUploadService fileUploadService;

    public List<CourseResponse> getAllCourses() {
        return courseRepository.findAll().stream()
                .map(courseMapper::toCourseResponse)
                .collect(Collectors.toList());
    }

    public CourseResponse getCourseById(Long courseId) {
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        return courseMapper.toCourseResponse(course);
    }

    public List<CourseResponse> getMyCourses(User user) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }

        var instructor = instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", user.getId().toString()));

        return courseRepository.findByInstructorId(instructor.getId()).stream()
                .map(courseMapper::toCourseResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public CourseResponse createCourse(User user, CourseRequest request) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }

        var instructor = instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", user.getId().toString()));

        var course = courseRepository.save(courseMapper.toCourse(request, instructor));
        return courseMapper.toCourseResponse(course);
    }

    @Transactional
    public CourseResponse updateCourse(User user, Long courseId, CourseRequest request) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }

        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }

        // Delete old image if it's being updated
        if (course.getImage() != null && request.getImage() != null && !course.getImage().equals(request.getImage())) {
            fileUploadService.deleteFile(course.getImage());
        }

        course.setTitle(request.getTitle());
        course.setSubtitle(request.getSubtitle());
        course.setImage(request.getImage());
        course.setDescription(request.getDescription());
        course.setDuration(request.getDuration());
        course.setPrice(request.getPrice());

        course = courseRepository.save(course);
        return courseMapper.toCourseResponse(course);
    }

    @Transactional
    public void deleteCourse(User user, Long courseId) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }

        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));

        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }

        if (course.getImage() != null) {
            fileUploadService.deleteFile(course.getImage());
        }

        courseRepository.delete(course);
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

        var enrollment = enrollmentRepository.save(courseMapper.toEnrollment(course, student));

        course.setStudentsCount(course.getStudentsCount() + 1);
        courseRepository.save(course);

        return courseMapper.toEnrollmentResponse(enrollment);
    }

    public List<EnrollmentResponse> getMyEnrollments(User user) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        final Student student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        return enrollmentRepository.findByStudentId(student.getId()).stream()
                .map(courseMapper::toEnrollmentResponse)
                .collect(Collectors.toList());
    }
}
