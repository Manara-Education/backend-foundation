package com.manara.backend.course.integration;

import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.model.EntitlementSource;
import com.manara.backend.course.repository.CourseEntitlementRepository;
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

import java.time.LocalDateTime;
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
    @Autowired protected CourseEntitlementRepository courseEntitlementRepository;
    @Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

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

    /**
     * Joins a learner to a course, the way a free checkout would: an enrollment saying they joined
     * and a perpetual entitlement saying they may open it.
     *
     * <p>Written through the repositories rather than through {@code CheckoutProcessor} so that a
     * test about update tracking is not also a test about payment. What matters here is the pair of
     * rows that exist afterwards, which is the same pair either route produces.
     */
    protected Enrollment enroll(User studentUser, Long courseId) {
        Student student = studentProfileOf(studentUser);
        Course course = reload(courseId);

        Enrollment enrollment = enrollmentRepository.saveAndFlush(
                Enrollment.builder().course(course).student(student).progress(0).enrolled(true).build());
        courseEntitlementRepository.saveAndFlush(CourseEntitlement.builder()
                .course(course)
                .student(student)
                .source(EntitlementSource.FREE)
                .startsAt(LocalDateTime.now().minusYears(1))
                .build());
        return enrollment;
    }

    /**
     * Backdates a whole course — itself, its modules, its lessons and its quizzes — so that a
     * learner can plausibly have enrolled before it was edited.
     *
     * <p>Without this a test is describing an impossibility. A course built inside the test method
     * is created *now*, so an enrollment placed a month ago predates the course's own existence, and
     * every row correctly reports itself as content the learner has never seen. The scenario these
     * tests mean is the ordinary one: a course that has been live for a while, a learner who joined
     * it, and an instructor who has since changed something.
     *
     * <p>By SQL for the same reason {@link #enrolledAt} is: {@code created_at} is
     * {@code updatable = false} on every one of these entities and must stay that way.
     */
    protected void courseExistedSince(Long courseId, LocalDateTime at) {
        jdbcTemplate.update(
                "UPDATE courses SET created_at = ?, content_updated_at = ?, "
                        + "last_published_at = CASE WHEN last_published_at IS NULL THEN NULL ELSE ? END "
                        + "WHERE id = ?", at, at, at, courseId);
        jdbcTemplate.update(
                "UPDATE course_modules SET created_at = ?, content_updated_at = ? WHERE course_id = ?",
                at, at, courseId);
        jdbcTemplate.update(
                "UPDATE lessons SET created_at = ?, content_updated_at = ? WHERE course_id = ?",
                at, at, courseId);
        // Quiz ownership is polymorphic, so there is no join from a course to its quizzes. All
        // three owner scopes are named explicitly, which is also what keeps this from touching
        // another course's quizzes by accident.
        jdbcTemplate.update(
                "UPDATE quizzes SET created_at = ?, content_updated_at = ? WHERE "
                        + "(owner_type = 'COURSE' AND owner_id = ?) "
                        + "OR (owner_type = 'MODULE' AND owner_id IN "
                        + "    (SELECT id FROM course_modules WHERE course_id = ?)) "
                        + "OR (owner_type = 'LESSON' AND owner_id IN "
                        + "    (SELECT id FROM lessons WHERE course_id = ?))",
                at, at, courseId, courseId, courseId);
    }

    /**
     * Moves an enrollment's instant, by SQL, because the column is {@code updatable = false} and
     * must stay that way — the badge is cleared by an instructor publishing, never by rewriting when
     * a learner joined.
     *
     * <p>Which is exactly why a test needs this: "enrolled before the change" and "enrolled after
     * it" are the two cases the whole feature turns on, and the only honest way to set them up
     * without sleeping is to place the enrollment where the scenario says it was.
     */
    protected void enrolledAt(Long enrollmentId, LocalDateTime at) {
        jdbcTemplate.update("UPDATE enrollments SET enrolled_at = ? WHERE id = ?", at, enrollmentId);
    }

    /** The learner-facing course screen, as this student sees it. */
    protected CourseDetailsResponse detailsFor(User studentUser, Long courseId) {
        return courseService.getCourseDetails(studentUser, courseId, CourseViewMode.ENROLLED);
    }

    /** The card this student would see in My Courses. */
    protected com.manara.backend.dashboard.dto.CourseViewResponse cardFor(User studentUser, Long courseId) {
        return dashboardService.getStudentCourses(studentUser).stream()
                .filter(card -> card.getId().equals(courseId))
                .findFirst()
                .orElseThrow();
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

    /** The stored root-lesson order of a flat course, read back from the database. */
    protected List<String> persistedRootLessonTitles(Long courseId) {
        return lessonRepository.findRootLessons(courseId).stream()
                .map(lesson -> lesson.getTitle())
                .toList();
    }

    protected List<Integer> persistedRootLessonPositions(Long courseId) {
        return lessonRepository.findRootLessons(courseId).stream()
                .map(lesson -> lesson.getOrderIndex())
                .toList();
    }

    /** The stored lesson order inside one module, read back from the database. */
    protected List<String> persistedModuleLessonTitles(Long courseId, Long moduleId) {
        return lessonRepository.findModuleLessons(courseId, moduleId).stream()
                .map(lesson -> lesson.getTitle())
                .toList();
    }

    protected List<Integer> persistedModuleLessonPositions(Long courseId, Long moduleId) {
        return lessonRepository.findModuleLessons(courseId, moduleId).stream()
                .map(lesson -> lesson.getOrderIndex())
                .toList();
    }

    protected List<Long> lessonIdsOf(InstructorCourseResponse course) {
        return course.getLessons().stream().map(lesson -> lesson.getId()).toList();
    }

    /** The lesson ids of one module of the editor response, in the order it returned them. */
    protected List<Long> moduleLessonIdsOf(InstructorCourseResponse course, int moduleIndex) {
        return course.getModules().get(moduleIndex).getLessons().stream()
                .map(lesson -> lesson.getId())
                .toList();
    }
}
