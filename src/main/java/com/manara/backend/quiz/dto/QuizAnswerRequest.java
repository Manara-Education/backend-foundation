package com.manara.backend.quiz.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One answer inside a {@link QuizSubmissionRequest}: the question, and the option chosen for it.
 *
 * <p>Ids are strings so a client can send back exactly the values it received in
 * {@link LearnerQuizResponse}. Nothing else is accepted — no score, no correctness flag — because
 * the server reads the answer key from its own rows.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QuizAnswerRequest {

    private String questionId;

    private String optionId;
}
