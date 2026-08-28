package com.manara.backend.course.service;

import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.ContentEntityType;
import com.manara.backend.course.model.CourseChange;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.repository.CourseChangeRepository;
import com.manara.backend.course.repository.EnrollmentRepository;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out what a course's changes look like to the person asking about it.
 *
 * <h2>Whose enrollment</h2>
 * The viewer's, resolved from the authenticated {@link User} through
 * {@link StudentRepository#findByUserId}. No student id, enrollment id or timestamp is ever taken
 * from a request — there is no parameter to pass one through, which is the strongest form the
 * guarantee can take. A learner can only ever be told about changes to a course they joined, since
 * the window is measured from their own row.
 *
 * <h2>One query</h2>
 * The enrollment is already loaded on the enrolled path; the change log costs one indexed range
 * scan over {@code (course_id, occurred_at)}, bounded below by the reader's enrollment instant, and
 * that is the entire read cost of this feature on the course-details page. Per-item state needs no
 * query at all — it is arithmetic on columns the aggregate loader has already fetched, which is why
 * a hundred-lesson course costs the same as a three-lesson one.
 *
 * <p>Nothing on the My Courses path comes through here. That page compares two loaded fields per
 * card via {@link Enrollment#hasCourseUpdates()} and reads no log at all.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseUpdateResolver {

    private final StudentRepository studentRepository;
    private final EnrollmentRepository enrollmentRepository;
    private final CourseChangeRepository courseChangeRepository;
    private final CourseChangeNarrator narrator;

    /**
     * @param user      the authenticated viewer — an instructor, an admin or a visitor yields the
     *                  not-enrolled window, which reports nothing
     * @param aggregate the course as it stands now, needed to tell content that was removed from
     *                  content that merely moved
     */
    public CourseUpdateWindow resolve(User user, CourseAggregate aggregate) {
        Optional<Enrollment> enrollment = enrollmentOf(user, aggregate.course().getId());
        if (enrollment.isEmpty()) {
            return CourseUpdateWindow.notEnrolled();
        }

        Enrollment learner = enrollment.get();
        List<CourseChange> changes =
                courseChangeRepository.findSince(aggregate.course().getId(), learner.getEnrolledAt());

        Map<CourseUpdateWindow.EntityKey, CourseChange> latest = latestPerEntity(changes);
        return new CourseUpdateWindow(learner, latest, removals(latest, aggregate), narrator);
    }

    private Optional<Enrollment> enrollmentOf(User user, Long courseId) {
        if (user == null || user.getRole() != Role.STUDENT) {
            return Optional.empty();
        }
        return studentRepository.findByUserId(user.getId())
                .flatMap(student -> enrollmentRepository.findByCourseIdAndStudentId(courseId, student.getId()));
    }

    /**
     * The newest row per subject.
     *
     * <p>The query returns newest first, so the first row seen for an entity is the one to keep. A
     * lesson edited three times since the learner enrolled is one line in their curriculum, not
     * three — they are being told what changed, not shown an audit trail.
     */
    private Map<CourseUpdateWindow.EntityKey, CourseChange> latestPerEntity(List<CourseChange> changes) {
        Map<CourseUpdateWindow.EntityKey, CourseChange> latest = new HashMap<>();
        for (CourseChange change : changes) {
            latest.putIfAbsent(
                    new CourseUpdateWindow.EntityKey(change.getEntityType(), change.getEntityId()),
                    change);
        }
        return latest;
    }

    /**
     * Removals that actually stuck.
     *
     * <p>A {@code REMOVED} row is only worth showing if the thing is really gone. It is checked
     * against the course as it stands rather than trusted, because a lesson can be deleted and a
     * later edit can put one back under the same title — and because a module deleted during a
     * structure switch may have had its lessons re-parented rather than destroyed.
     *
     * <p>Ordered oldest first, the opposite of the query, so a learner reads the losses in the order
     * they happened.
     */
    private List<CourseChange> removals(Map<CourseUpdateWindow.EntityKey, CourseChange> latest,
                                        CourseAggregate aggregate) {
        Set<CourseUpdateWindow.EntityKey> live = liveKeys(aggregate);

        List<CourseChange> removed = new ArrayList<>();
        for (CourseChange change : latest.values()) {
            if (change.getChangeType() == ContentChangeType.REMOVED
                    && !live.contains(new CourseUpdateWindow.EntityKey(
                            change.getEntityType(), change.getEntityId()))) {
                removed.add(change);
            }
        }
        removed.sort((left, right) -> left.getOccurredAt().compareTo(right.getOccurredAt()));
        return List.copyOf(removed);
    }

    private Set<CourseUpdateWindow.EntityKey> liveKeys(CourseAggregate aggregate) {
        Set<CourseUpdateWindow.EntityKey> live = new HashSet<>();
        aggregate.modules().forEach(module ->
                live.add(new CourseUpdateWindow.EntityKey(ContentEntityType.MODULE, module.getId())));
        aggregate.lessons().forEach(lesson ->
                live.add(new CourseUpdateWindow.EntityKey(ContentEntityType.LESSON, lesson.getId())));

        // allQuizzes flattens the three owner scopes, and each quiz reports whether a learner
        // would call it a quiz or an exam — the same split the change log recorded it under.
        aggregate.allQuizzes().forEach(quiz ->
                live.add(new CourseUpdateWindow.EntityKey(quiz.contentType(), quiz.getId())));
        return live;
    }
}
