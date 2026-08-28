package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * One question of a graded attempt, as returned <em>after</em> submission.
 *
 * <p>This is the only learner-facing type that carries a correct answer, and it exists only inside
 * {@link QuizAttemptResponse} — a value that is produced by grading a submission and by nothing
 * else. Everything the learner sees before submitting comes from
 * {@link LearnerQuizQuestionResponse}, which has no such field.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class QuizAttemptAnswerResponse {

    private String questionId;

    private String selectedOptionId;

    private String correctOptionId;

    /**
     * The question, the chosen answer and the answer key in the words they carried at submission.
     *
     * <p>Copied onto the attempt row rather than read back from the quiz, so a result that is
     * reviewed after the instructor has reworded the question, moved the answer key or deleted the
     * chosen option still describes the quiz the learner actually sat. The ids above go on naming
     * the authoring rows while they exist; these three are what survive them.
     */
    private String questionText;

    private String selectedOptionText;

    private String correctOptionText;

    private Boolean correct;

    /** The instructor's explanation, when they wrote one. */
    private String explanation;
}
