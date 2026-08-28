package com.manara.backend.course.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * One lesson scope's lessons, in the order the instructor just arranged them.
 *
 * <p>The sibling counterpart of {@link ModuleOrderRequest}, and deliberately the same shape: ids
 * only, positions derived from the array. It serves both lesson scopes a course can have — the
 * root lessons of a {@code FLAT} course and the lessons inside one module — because they are the
 * same operation on two different parents, and the parent is named by the path rather than the
 * body. A lesson cannot therefore be moved between scopes by a reorder, which is what keeps this
 * command's meaning "arrange these siblings" rather than "restructure the course".
 *
 * <p>The list must name every lesson of the scope exactly once. A reorder built from a lesson list
 * that has since changed is a stale reorder, and it is refused rather than partially applied.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LessonOrderRequest {

    @NotNull(message = "{validation.course.lessonOrder.required}")
    private List<Long> lessonIds;
}
