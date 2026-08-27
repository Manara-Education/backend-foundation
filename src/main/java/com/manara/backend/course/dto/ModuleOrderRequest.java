package com.manara.backend.course.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * A course's modules, in the order the instructor just arranged them.
 *
 * <p>Ids only. Positions are derived from the array — the first id becomes position 0, the second
 * 1, and so on — so a client can never submit a set of positions with a gap, a duplicate or a
 * negative number in it, and the stored order is contiguous by construction rather than by
 * validation.
 *
 * <p>The list must name every module of the course exactly once. That is what makes the command
 * safe to apply to a course that has changed underneath the client: a reorder whose module set no
 * longer matches the course's is a stale reorder, and it is refused rather than partially applied.
 */
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ModuleOrderRequest {

    @NotNull(message = "{validation.course.moduleOrder.required}")
    private List<Long> moduleIds;
}
