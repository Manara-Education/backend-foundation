package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.file.FileUploadService;
import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.mapper.CourseAggregateMapper;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.view.CourseDetailsViewRegistry;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Course authoring and browsing.
 *
 * <p>Every aggregate mutation runs in one transaction covering the course, its modules, lessons,
 * quizzes, questions, options and plans. Validation is complete before synchronization starts, so a
 * rejected payload never leaves a half-rearranged course behind, and a failure anywhere in the
 * nested content rolls the whole edit back.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final LessonRepository lessonRepository;
    private final InstructorRepository instructorRepository;
    private final StudentRepository studentRepository;
    private final CourseMapper courseMapper;
    private final CourseAggregateMapper courseAggregateMapper;
    private final CourseAggregateLoader courseAggregateLoader;
    private final CourseValidator courseValidator;
    private final CourseContentSynchronizer courseContentSynchronizer;
    private final CourseDetailsViewRegistry courseDetailsViewRegistry;
    private final FileUploadService fileUploadService;

    /** Catalogue for instructors and admins — drafts included. */
    public List<CourseResponse> getAllCourses() {
        return courseRepository.findAllWithInstructor().stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    /** Catalogue for learners. Drafts are an instructor's work in progress and stay hidden. */
    public List<CourseResponse> getPublishedCourses() {
        return courseRepository.findAllByStatusWithInstructor(CourseStatus.PUBLISHED).stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    public CourseDetailsResponse getCourseDetails(User user, Long courseId, CourseViewMode mode) {
        var course = findPublishedCourse(courseId);
        var aggregate = courseAggregateLoader.load(course);
        var progression = courseDetailsViewRegistry.get(mode).resolveProgression(user, aggregate);
        return courseAggregateMapper.toCourseDetailsResponse(aggregate, progression);
    }

    public List<CourseResponse> getMyCourses(User user) {
        var instructor = requireInstructor(user);
        return courseRepository.findByInstructorIdWithInstructor(instructor.getId()).stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    /** The complete editor model: content tree, exams with their answer keys, pricing and status. */
    public InstructorCourseResponse getCourseForEditing(User user, Long courseId) {
        var course = requireOwnedCourse(user, courseId);
        return courseAggregateMapper.toInstructorCourseResponse(courseAggregateLoader.load(course));
    }

    @Transactional
    public InstructorCourseResponse createCourse(User user, CourseRequest request) {
        var instructor = requireInstructor(user);
        var settings = courseValidator.resolveAndValidate(request, null, () -> 0);

        var course = courseRepository.save(courseMapper.toCourse(request, instructor, settings));
        courseContentSynchronizer.sync(course, request, settings);

        return saveAndRespond(course);
    }

    @Transactional
    public InstructorCourseResponse updateCourse(User user, Long courseId, CourseRequest request) {
        var course = requireOwnedCourse(user, courseId);
        var settings = courseValidator.resolveAndValidate(request, course, () -> activeLessonCount(course));

        String previousImage = course.getImage();

        course.setTitle(request.getTitle().trim());
        course.setSubtitle(request.getSubtitle());
        course.setImage(request.getImage());
        course.setDescription(request.getDescription());
        course.setDuration(request.getDuration());
        course.setStructure(settings.structure());
        course.setStatus(settings.status());
        course.setAccessType(settings.accessType());
        course.setPurchasePrice(settings.purchasePrice());

        courseContentSynchronizer.sync(course, request, settings);

        var response = saveAndRespond(course);

        // Deleting the replaced upload last: the file is gone for good, so it only happens once the
        // rest of the edit has been accepted.
        if (previousImage != null && request.getImage() != null && !previousImage.equals(request.getImage())) {
            fileUploadService.deleteFile(previousImage);
        }
        return response;
    }

    /**
     * Flushes before reading the aggregate back so freshly created modules, lessons, quizzes,
     * questions and options are returned with their final persisted ids.
     */
    private InstructorCourseResponse saveAndRespond(Course course) {
        courseRepository.saveAndFlush(course);
        return courseAggregateMapper.toInstructorCourseResponse(courseAggregateLoader.load(course));
    }

    @Transactional
    public EnrollmentResponse enrollInCourse(User user, Long courseId) {
        if (user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }

        var student = studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));

        var course = findPublishedCourse(courseId);

        if (enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId()).isPresent()) {
            throw new BusinessException("error.course.alreadyEnrolled");
        }

        var enrollment = enrollmentRepository.save(courseMapper.toEnrollment(course, student));

        course.setStudentsCount(course.getStudentsCount() + 1);
        courseRepository.save(course);

        return courseMapper.toEnrollmentResponse(enrollment);
    }

    @Transactional
    public EnrollmentResponse processCheckoutAndEnroll(User user, Long courseId, CheckoutRequest request) {
        var course = findPublishedCourse(courseId);

        // Access type is now the authority on whether money is involved; it used to be inferred
        // from a non-zero price, which no longer describes subscription courses.
        if (course.getAccessType() != CourseAccessType.FREE) {
            validatePaymentDetails(request);
        }

        return enrollInCourse(user, courseId);
    }

    private void validatePaymentDetails(CheckoutRequest request) {
        if (request == null) {
            throw new BusinessException("error.payment.required");
        }
        String card = request.getCardNumber() == null ? "" : request.getCardNumber().replaceAll("\\s+", "");
        if (card.length() < 15 || card.length() > 19) {
            throw new BusinessException("error.payment.invalidCard");
        }
        if (request.getCvc() == null || request.getCvc().length() < 3 || request.getCvc().length() > 4) {
            throw new BusinessException("error.payment.invalidCvc");
        }
        if (request.getExpiry() == null || !request.getExpiry().contains("/")) {
            throw new BusinessException("error.payment.invalidExpiry");
        }
    }

    private Instructor requireInstructor(User user) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        return instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", user.getId().toString()));
    }

    /**
     * The single ownership gate for course authoring. Every nested module, lesson and quiz is
     * reached through the course checked here — none of them is ever looked up by a client-supplied
     * id on its own.
     */
    private Course requireOwnedCourse(User user, Long courseId) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }
        return course;
    }

    /** Drafts are indistinguishable from a missing course on the learner side. */
    private Course findPublishedCourse(Long courseId) {
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (course.getStatus() != CourseStatus.PUBLISHED) {
            throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
        }
        return course;
    }

    private int activeLessonCount(Course course) {
        return course.getStructure() == CourseStructure.MODULES
                ? lessonRepository.countByCourseIdAndModuleIsNotNull(course.getId())
                : lessonRepository.countByCourseIdAndModuleIsNull(course.getId());
    }
}
