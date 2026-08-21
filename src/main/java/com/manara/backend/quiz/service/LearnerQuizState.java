package com.manara.backend.quiz.service;

import com.manara.backend.quiz.model.QuizAttempt;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One learner's standing on one quiz: may they take it, and how did their attempts go.
 *
 * <p>Availability is a progression decision and is supplied by the caller that knows the
 * curriculum; the attempt figures are summarised from the stored attempts. Keeping both in one
 * value is what lets a single object answer every question the quiz screen asks.
 */
public record LearnerQuizState(
        boolean available,
        int attemptCount,
        boolean passed,
        Integer bestScore,
        Long lastAttemptId,
        LocalDateTime lastSubmittedAt) {

    /** A quiz the viewer may not take and has never attempted. */
    public static LearnerQuizState locked() {
        return new LearnerQuizState(false, 0, false, null, null, null);
    }

    /** A quiz the viewer may take but has never attempted — an instructor previewing, typically. */
    public static LearnerQuizState unlocked() {
        return new LearnerQuizState(true, 0, false, null, null, null);
    }

    /**
     * Summarises a learner's attempts at one quiz. {@code attempts} arrives oldest first, so the
     * last element is the most recent submission.
     */
    public static LearnerQuizState of(boolean available, List<QuizAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return new LearnerQuizState(available, 0, false, null, null, null);
        }

        boolean passed = false;
        int bestScore = 0;
        for (QuizAttempt attempt : attempts) {
            passed |= Boolean.TRUE.equals(attempt.getPassed());
            bestScore = Math.max(bestScore, attempt.getScore());
        }

        QuizAttempt latest = attempts.get(attempts.size() - 1);
        return new LearnerQuizState(available, attempts.size(), passed, bestScore,
                latest.getId(), latest.getSubmittedAt());
    }

    public LearnerQuizState withAvailability(boolean newAvailability) {
        return new LearnerQuizState(newAvailability, attemptCount, passed, bestScore, lastAttemptId, lastSubmittedAt);
    }
}
