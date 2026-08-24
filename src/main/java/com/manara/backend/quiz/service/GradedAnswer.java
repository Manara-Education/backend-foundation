package com.manara.backend.quiz.service;

import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizQuestion;

/**
 * One graded question of an attempt: what the student chose, and whether it was the answer key.
 *
 * <p>Holds entities rather than ids because both the persisted answer row and the result DTO are
 * built from it, and the caller would otherwise have to look the same rows up again.
 */
public record GradedAnswer(QuizQuestion question, QuizOption selectedOption, boolean correct) {
}
