package com.manara.backend.course.dto;

import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.quiz.dto.LearnerQuizResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Learner view of a module — no answer keys anywhere in the tree.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LearnerModuleResponse {

    private Long id;
    private String title;
    private String description;
    private Integer orderIndex;
    private List<LessonResponse> lessons;
    private LearnerQuizResponse quiz;

    /** True while an earlier module is unfinished, which is what keeps this one shut. */
    private Boolean locked;

    /**
     * Whether this module is new or updated to the learner reading it.
     *
     * <p>Describes the module itself — its title, its description — and not its contents. A module
     * whose third lesson changed is not itself updated; that lesson is, and says so on its own row.
     * Marking the parent as well is how a curriculum ends up with every row lit and none of them
     * meaning anything.
     */
    private ContentChangeResponse change;
}
