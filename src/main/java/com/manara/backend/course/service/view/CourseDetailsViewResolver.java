package com.manara.backend.course.service.view;

import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.user.model.User;

/**
 * Decides what a given audience is allowed to see of a course, and how far through it they are.
 *
 * <p>Resolvers own the access rules of their view and nothing else. They answer with a
 * {@link CourseProgression} — the same value every other learner-facing path uses — so building the
 * response tree stays the mapper's job and one set of rules decides every lock in the product.
 */
public interface CourseDetailsViewResolver {

    CourseViewMode mode();

    CourseProgression resolveProgression(User user, CourseAggregate aggregate);
}
