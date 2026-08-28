package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V10, against attempts that were already submitted.
 *
 * <p>The migration has to do two things to a database that already holds quiz history. Every
 * existing attempt has to be given the title of the quiz it points at, so that an attempt submitted
 * before this release is still readable once its quiz is deleted — otherwise the migration only
 * postpones the loss it exists to prevent. And {@code quiz_id} has to stop cascading, whatever name
 * the constraint happens to carry on the database being migrated.
 *
 * <p>The nullability half is asserted in {@link QuizAttemptSnapshotMigrationTest}, beside V9's, for
 * the same reason the migrations are numbered in sequence: they are one decision told in two parts.
 */
class QuizAttemptQuizDetachMigrationTest extends AbstractPostgresBackedTest {

    /** Copied from V10. If the migration's back-fill changes, this must too. */
    private static final String BACKFILL_QUIZ_TITLE = """
            UPDATE public.quiz_attempts a
            SET quiz_title = q.title
            FROM public.quizzes q
            WHERE a.quiz_id = q.id
              AND a.quiz_title IS NULL
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("an attempt written before the column existed is given the quiz's title")
    void backfillsTheTitleFromTheQuizStillThere() {
        transactionTemplate.executeWithoutResult(status -> {
            long attemptId = seedAttemptWithoutTitle();

            jdbc.update(BACKFILL_QUIZ_TITLE);

            assertThat(jdbc.queryForObject(
                    "SELECT quiz_title FROM quiz_attempts WHERE id = ?", String.class, attemptId))
                    .isEqualTo("Seeded quiz");

            status.setRollbackOnly();
        });
    }

    /**
     * A title already written is never overwritten.
     *
     * <p>The {@code IS NULL} guard is what makes the migration safe to re-run and safe to run while
     * an instance of the new build is already writing titles — re-deriving from the current quiz
     * would replace what the learner actually sat with whatever the quiz has since been renamed to.
     */
    @Test
    @DisplayName("a title that is already there is left alone")
    void doesNotOverwriteAnExistingTitle() {
        transactionTemplate.executeWithoutResult(status -> {
            long attemptId = seedAttemptWithoutTitle();
            jdbc.update("UPDATE quiz_attempts SET quiz_title = 'As it was sat' WHERE id = ?", attemptId);

            jdbc.update(BACKFILL_QUIZ_TITLE);

            assertThat(jdbc.queryForObject(
                    "SELECT quiz_title FROM quiz_attempts WHERE id = ?", String.class, attemptId))
                    .isEqualTo("As it was sat");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("quiz_id is nullable now")
    void theQuizReferenceIsNullable() {
        assertThat(jdbc.queryForObject("""
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'quiz_attempts'
                  AND column_name = 'quiz_id'
                """, String.class))
                .isEqualTo("YES");
    }

    @Test
    @DisplayName("deleting the quiz leaves the attempt standing, reference cleared, title kept")
    void deletingTheQuizNoLongerDeletesTheAttempt() {
        transactionTemplate.executeWithoutResult(status -> {
            long attemptId = seedAttemptWithoutTitle();
            jdbc.update(BACKFILL_QUIZ_TITLE);
            Long quizId = jdbc.queryForObject(
                    "SELECT quiz_id FROM quiz_attempts WHERE id = ?", Long.class, attemptId);

            jdbc.update("DELETE FROM quizzes WHERE id = ?", quizId);

            var row = jdbc.queryForMap(
                    "SELECT quiz_id, quiz_title, score, passed FROM quiz_attempts WHERE id = ?", attemptId);
            assertThat(row.get("quiz_id")).isNull();
            assertThat(row).containsEntry("quiz_title", "Seeded quiz");
            assertThat(row).containsEntry("score", 100);
            assertThat(row).containsEntry("passed", true);

            status.setRollbackOnly();
        });
    }

    /**
     * Two learners can each hold a detached attempt without colliding.
     *
     * <p>{@code uk_quiz_attempts_student_quiz_number} is {@code (student_id, quiz_id,
     * attempt_number)}. PostgreSQL treats NULLs as distinct in a unique index, so detached attempts
     * stop participating in it — asserted rather than assumed, because if it were otherwise the
     * deletion of a popular quiz would fail outright on the second learner.
     */
    @Test
    @DisplayName("the uniqueness rule does not trip over several detached attempts")
    void detachedAttemptsDoNotCollide() {
        transactionTemplate.executeWithoutResult(status -> {
            long first = seedAttemptWithoutTitle();
            Long quizId = jdbc.queryForObject(
                    "SELECT quiz_id FROM quiz_attempts WHERE id = ?", Long.class, first);
            long second = seedAttemptFor(quizId);

            jdbc.update("DELETE FROM quizzes WHERE id = ?", quizId);

            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM quiz_attempts WHERE id IN (?, ?) AND quiz_id IS NULL",
                    Integer.class, first, second)).isEqualTo(2);

            status.setRollbackOnly();
        });
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    /** An attempt written the way the previous build wrote them: a quiz reference and no title. */
    private long seedAttemptWithoutTitle() {
        Long instructorUserId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Instructor', ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, "v11-instructor-" + System.nanoTime() + "@x.test");
        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, instructorUserId);

        Long courseId = jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES ('Quizzed', 'seeded', 'PUBLISHED', 'FLAT', 'FREE', 0, now(), ?) RETURNING id
                """, Long.class, instructorId);

        Long quizId = jdbc.queryForObject("""
                INSERT INTO quizzes (title, owner_type, owner_id, passing_score, created_at)
                VALUES ('Seeded quiz', 'COURSE', ?, 50, now()) RETURNING id
                """, Long.class, courseId);

        return seedAttemptFor(quizId);
    }

    /** Another learner's attempt at an existing quiz, in that quiz's own course. */
    private long seedAttemptFor(Long quizId) {
        Long studentUserId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Learner', ?, 'x', true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, "v11-learner-" + System.nanoTime() + "@x.test");
        Long studentId = jdbc.queryForObject(
                "INSERT INTO students (user_id) VALUES (?) RETURNING id", Long.class, studentUserId);
        Long courseId = jdbc.queryForObject(
                "SELECT owner_id FROM quizzes WHERE id = ?", Long.class, quizId);

        return jdbc.queryForObject("""
                INSERT INTO quiz_attempts (quiz_id, student_id, course_id, attempt_number, correct_count,
                                           total_questions, score, passing_score, passed, submitted_at)
                VALUES (?, ?, ?, 1, 1, 1, 100, 50, true, now()) RETURNING id
                """, Long.class, quizId, studentId, courseId);
    }
}
