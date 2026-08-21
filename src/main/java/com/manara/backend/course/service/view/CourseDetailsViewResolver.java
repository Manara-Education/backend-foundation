package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.user.model.User;

import java.util.Set;

/**
 * Decides what a given audience is allowed to see of a course, and how much of it they have
 * already completed.
 *
 * <p>Resolvers own the access rules of their view; building the response tree is the mapper's job,
 * which is why this returns completion state rather than lesson DTOs — the same lessons now appear
 * under modules as well as directly under a course, and there is one place that assembles both.
 */
public interface CourseDetailsViewResolver {

    CourseViewMode mode();

    /**
     * @return the lessons this user has completed, or {@code null} when completion state does not
     * apply to the view
     */
    Set<Long> resolveCompletedLessonIds(User user, Long courseId);
}
