package com.manara.backend.quiz.service;

import java.util.List;

/**
 * The outcome of grading one submission — the only place a score or a pass decision is produced.
 *
 * <p>Every field is derived from the stored quiz and the submitted option ids. Nothing here can be
 * influenced by what the client claims its result was.
 */
public record GradedQuiz(
        int correctCount,
        int totalQuestions,
        int score,
        int passingScore,
        boolean passed,
        List<GradedAnswer> answers) {
}
