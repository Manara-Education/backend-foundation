package com.manara.backend.course.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.profile.model.Student;

/**
 * Who is looking at a course, and everything that follows from it.
 *
 * <p>Resolved once by {@link LearnerCourseAccess} and passed around, so a request establishes the
 * viewer's identity, their enrolment and their position in the curriculum a single time rather than
 * re-deriving any of it at each layer.
 *
 * @param student    the learner behind the request, or {@code null} when the viewer is not one —
 *                   an instructor previewing their own course, typically
 * @param enrollment present only for an enrolled learner
 */
public record CourseViewer(
        Course course,
        Student student,
        Enrollment enrollment,
        CourseAggregate aggregate,
        CourseProgression progression) {

    public boolean isEnrolled() {
        return enrollment != null;
    }
}
