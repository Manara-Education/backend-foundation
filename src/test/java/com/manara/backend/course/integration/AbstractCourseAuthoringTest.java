package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.course.service.CourseService;
import com.manara.backend.dashboard.service.DashboardService;
import com.manara.backend.db.AbstractPostgresBackedTest;
import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import com.manara.backend.user.repository.UserRepository;
import com.manara.backend.video.service.VideoMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static com.manara.backend.course.integration.CourseAuthoringFixtures.instructor;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.student;
import static com.manara.backend.course.integration.CourseAuthoringFixtures.user;

/**
 * A real application, a real PostgreSQL, and a real transaction per call.
 *
 * <p>Deliberately not {@code @Transactional}. A test that runs inside its own transaction sees its
 * writes through the same persistence context that made them, which is precisely the illusion these
 * tests exist to rule out: "the order changed" has to mean the rows in the database changed, not
 * that the objects in the session did. Every service call here therefore commits, and every
 * assertion re-reads through a repository, which opens a session of its own.
 *
 * <p>Nothing is cleaned up between tests, and nothing needs to be: each test creates its own
 * instructor and its own course, and asserts only about those.
 */
abstract class AbstractCourseAuthoringTest extends AbstractPostgresBackedTest {

    @Autowired protected CourseService courseService;
    @Autowired protected CourseRepository courseRepository;
    @Autowired protected CourseModuleRepository courseModuleRepository;
    @Autowired protected LessonRepository lessonRepository;
    @Autowired protected CompletedLessonRepository completedLessonRepository;
    @Autowired protected EnrollmentRepository enrollmentRepository;
    @Autowired protected UserRepository userRepository;
    @Autowired protected InstructorRepository instructorRepository;
    @Autowired protected StudentRepository studentRepository;
    @Autowired protected DashboardService dashboardService;

    /**
     * Stubbed because it reaches YouTube and Vimeo over the network. Its absence is also what makes
     * a lesson's duration stay at zero — which is exactly the production state that made a course
     * uneditable, so these tests run against it rather than around it.
     */
    @MockitoBean protected VideoMetadataService videoMetadataService;

    protected User instructorUser;
    protected Instructor instructorProfile;

    @BeforeEach
    void createInstructor() {
        instructorUser = userRepository.save(user(Role.INSTRUCTOR));
        instructorProfile = instructorRepository.save(instructor(instructorUser));
    }

    protected User newInstructorUser() {
        User other = userRepository.save(user(Role.INSTRUCTOR));
        instructorRepository.save(instructor(other));
        return other;
    }

    protected User newStudentUser() {
        User other = userRepository.save(user(Role.STUDENT));
        studentRepository.save(student(other));
        return other;
    }

    protected Student studentProfileOf(User user) {
        return studentRepository.findByUserId(user.getId()).orElseThrow();
    }

    /** Re-reads the course from the database, outside any session this test already holds. */
    protected Course reload(Long courseId) {
        return courseRepository.findById(courseId).orElseThrow();
    }

    /** The stored module order, read back from the database. */
    protected List<String> persistedModuleTitles(Long courseId) {
        return courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(module -> module.getTitle())
                .toList();
    }

    /** The stored module positions, read back from the database, in curriculum order. */
    protected List<Integer> persistedModulePositions(Long courseId) {
        return courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(courseId).stream()
                .map(module -> module.getOrderIndex())
                .toList();
    }

    protected List<Long> moduleIdsOf(InstructorCourseResponse course) {
        return course.getModules().stream().map(module -> module.getId()).toList();
    }
}
