package com.manara.backend.quiz.service;

import com.manara.backend.quiz.model.Quiz;

/**
 * What {@link QuizService#sync} did, not just what it produced.
 *
 * <p>The quiz alone cannot answer "did this save change anything?" — an unchanged quiz and a
 * rewritten one are the same object afterwards. Callers that own a content version signal need the
 * difference, and computing it here, where the before-and-after are both in hand, is the only
 * place it is cheap and exact.
 *
 * @param quiz    the owner's quiz after the sync, or {@code null} when it has none
 * @param changed whether the sync created, removed, or actually altered the quiz
 */
public record QuizSyncResult(Quiz quiz, boolean changed) {

    static QuizSyncResult unchanged(Quiz quiz) {
        return new QuizSyncResult(quiz, false);
    }

    static QuizSyncResult changed(Quiz quiz) {
        return new QuizSyncResult(quiz, true);
    }
}
