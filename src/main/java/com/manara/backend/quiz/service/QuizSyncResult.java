package com.manara.backend.quiz.service;

import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.quiz.model.Quiz;

/**
 * What {@link QuizService#sync} did, not just what it produced.
 *
 * <p>The quiz alone cannot answer "did this save change anything?" — an unchanged quiz and a
 * rewritten one are the same object afterwards. Callers that own a content version signal need the
 * difference, and computing it here, where the before-and-after are both in hand, is the only
 * place it is cheap and exact.
 *
 * <p>{@link #quiz} is populated even when the quiz was deleted, which is the one case the caller
 * cannot recover it for itself: a removal has to be recorded against something, and after this
 * returns there is nothing left to record it against.
 *
 * @param quiz    the quiz this sync acted on, whatever it did to it, or {@code null} when the owner
 *                had none and still has none
 * @param outcome how to describe what happened, or {@code null} when nothing did
 */
public record QuizSyncResult(Quiz quiz, ContentChangeType outcome) {

    static QuizSyncResult unchanged(Quiz quiz) {
        return new QuizSyncResult(quiz, null);
    }

    static QuizSyncResult of(Quiz quiz, ContentChangeType outcome) {
        return new QuizSyncResult(quiz, outcome);
    }

    /** Whether the sync created, removed, or actually altered the quiz. */
    public boolean changed() {
        return outcome != null;
    }
}
