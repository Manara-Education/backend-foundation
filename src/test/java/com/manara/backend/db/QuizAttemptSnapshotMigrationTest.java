package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V9, against attempts that were already submitted.
 *
 * <p>Two things have to be true of a database that already has quiz history in it. The snapshot
 * columns have to be back-filled from the rows the answers still point at — otherwise every attempt
 * submitted before this release becomes unreadable the first time its quiz is edited, which is the
 * exact failure the migration exists to prevent, merely postponed. And the two foreign keys have to
 * stop cascading, whatever names they happen to carry on the database being migrated.
 *
 * <p>That second point is why the migration reads {@code pg_constraint} rather than dropping by
 * name: V1 recorded Hibernate-generated names, and a database built by an older {@code ddl-auto}
 * run carries different generated names for the same two constraints. A {@code DROP ... IF EXISTS}
 * that missed would leave the cascade in place beside the new rule and go on deleting history
 * silently. This asserts the outcome, not the statement.
 */
class QuizAttemptSnapshotMigrationTest extends AbstractPostgresBackedTest {

    /** Copied from V9. If the migration's back-fill changes, this must too. */
    private static final String BACKFILL_QUESTION_TEXT = """
            UPDATE public.quiz_attempt_answers a
            SET question_text = q.text
            FROM public.quiz_questions q
            WHERE a.question_id = q.id
              AND a.question_text IS NULL
            """;

    private static final String BACKFILL_SELECTED_TEXT = """
            UPDATE public.quiz_attempt_answers a
            SET selected_option_text = o.text
            FROM public.quiz_options o
            WHERE a.selected_option_id = o.id
              AND a.selected_option_text IS NULL
            """;

    private static final String BACKFILL_CORRECT_TEXT = """
            UPDATE public.quiz_attempt_answers a
            SET correct_option_text = k.text
            FROM public.quiz_options k
            WHERE k.question_id = a.question_id
              AND k.is_correct
              AND a.correct_option_text IS NULL
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("an answer row written before the columns existed is given its snapshot")
    void backfillsFromTheRowsStillThere() {
        transactionTemplate.executeWithoutResult(status -> {
            Seeded seeded = seedAnswerWithoutSnapshot();

            jdbc.update(BACKFILL_QUESTION_TEXT);
            jdbc.update(BACKFILL_SELECTED_TEXT);
            jdbc.update(BACKFILL_CORRECT_TEXT);

            var row = jdbc.queryForMap(
                    "SELECT question_text, selected_option_text, correct_option_text "
                            + "FROM quiz_attempt_answers WHERE attempt_id = ?", seeded.attemptId());

            assertThat(row).containsEntry("question_text", "The seeded question");
            assertThat(row).containsEntry("selected_option_text", "Chosen");
            assertThat(row).containsEntry("correct_option_text", "Chosen");

            status.setRollbackOnly();
        });
    }

    /**
     * A snapshot already written is never overwritten by the back-fill.
     *
     * <p>The {@code IS NULL} guard is what makes the migration safe to re-run and safe to run while
     * an instance of the new build is already writing snapshots — a re-derivation from the current
     * quiz would replace a true record of what the learner saw with the current wording.
     */
    @Test
    @DisplayName("a snapshot that is already there is left alone")
    void doesNotOverwriteAnExistingSnapshot() {
        transactionTemplate.executeWithoutResult(status -> {
            Seeded seeded = seedAnswerWithoutSnapshot();
            jdbc.update("UPDATE quiz_attempt_answers SET question_text = 'As it was asked' "
                    + "WHERE attempt_id = ?", seeded.attemptId());

            jdbc.update(BACKFILL_QUESTION_TEXT);

            assertThat(jdbc.queryForObject(
                    "SELECT question_text FROM quiz_attempt_answers WHERE attempt_id = ?",
                    String.class, seeded.attemptId())).isEqualTo("As it was asked");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("both content references are nullable now")
    void theReferencesAreNullable() {
        assertThat(jdbc.queryForList("""
                SELECT column_name, is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'quiz_attempt_answers'
                  AND column_name IN ('question_id', 'selected_option_id')
                """))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("is_nullable")).isEqualTo("YES"));
    }

    @Test
    @DisplayName("neither reference cascades any more; both clear themselves")
    void theReferencesSetNullInsteadOfCascading() {
        assertThat(jdbc.queryForList("""
                SELECT c.conname, c.confdeltype, referenced.relname AS referenced
                FROM pg_constraint c
                         JOIN pg_class t ON t.oid = c.conrelid
                         JOIN pg_namespace n ON n.oid = t.relnamespace
                         JOIN pg_class referenced ON referenced.oid = c.confrelid
                WHERE c.contype = 'f' AND n.nspname = 'public'
                  AND t.relname = 'quiz_attempt_answers'
                  AND referenced.relname IN ('quiz_questions', 'quiz_options')
                """))
                .as("exactly two, and no leftover cascading twin under an older generated name")
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.get("confdeltype"))
                        .as("'n' is SET NULL; 'c' would be the cascade that deleted the history")
                        .isEqualTo("n"));
    }

    /**
     * The attempt's own reference to its quiz no longer cascades either — see {@code V10}.
     *
     * <p>V9 left this one alone and said why: "an attempt at a quiz that no longer exists has
     * nothing left to describe". V9 is what made that false. Once the answer rows carry their own
     * question text, chosen text and answer key, the attempt describes itself, and the last
     * cascade was deleting a complete record for no remaining reason.
     *
     * <p>Kept in this file rather than moved: the two rules are one decision, and a reader who
     * finds the SET NULL on the answers should find the SET NULL on the header beside it.
     *
     * @see QuizAttemptQuizDetachMigrationTest
     */
    @Test
    @DisplayName("the attempt's own reference to its quiz clears itself too")
    void theQuizReferenceSetsNull() {
        assertThat(jdbc.queryForList("""
                SELECT c.confdeltype
                FROM pg_constraint c
                         JOIN pg_class t ON t.oid = c.conrelid
                         JOIN pg_class referenced ON referenced.oid = c.confrelid
                WHERE c.contype = 'f' AND t.relname = 'quiz_attempts'
                  AND referenced.relname = 'quizzes'
                """))
                .as("exactly one, and no leftover cascading twin under an older generated name")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.get("confdeltype"))
                        .as("'n' is SET NULL; 'c' would be the cascade that deleted the attempt")
                        .isEqualTo("n"));
    }

    @Test
    @DisplayName("deleting the chosen option leaves the answer row standing, reference cleared")
    void deletingAnOptionNoLongerDeletesTheAnswer() {
        transactionTemplate.executeWithoutResult(status -> {
            Seeded seeded = seedAnswerWithoutSnapshot();
            jdbc.update(BACKFILL_SELECTED_TEXT);

            jdbc.update("DELETE FROM quiz_options WHERE id = ?", seeded.optionId());

            var row = jdbc.queryForMap(
                    "SELECT selected_option_id, selected_option_text, is_correct "
                            + "FROM quiz_attempt_answers WHERE attempt_id = ?", seeded.attemptId());
            assertThat(row.get("selected_option_id")).isNull();
            assertThat(row).containsEntry("selected_option_text", "Chosen");
            assertThat(row).containsEntry("is_correct", true);

            status.setRollbackOnly();
        });
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private record Seeded(long attemptId, long questionId, long optionId) {
    }

    /** An attempt written the way the previous build wrote them: references only, no snapshot. */
    private Seeded seedAnswerWithoutSnapshot() {
        Long instructorUserId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Instructor', ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, "v9-instructor-" + System.nanoTime() + "@x.test");
        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, instructorUserId);

        Long studentUserId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Learner', ?, 'x', true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, "v9-learner-" + System.nanoTime() + "@x.test");
        Long studentId = jdbc.queryForObject(
                "INSERT INTO students (user_id) VALUES (?) RETURNING id", Long.class, studentUserId);

        Long courseId = jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id)
                VALUES ('Quizzed', 'seeded', 'PUBLISHED', 'FLAT', 'FREE', 0, now(), ?) RETURNING id
                """, Long.class, instructorId);

        Long quizId = jdbc.queryForObject("""
                INSERT INTO quizzes (title, owner_type, owner_id, passing_score, created_at)
                VALUES ('Seeded quiz', 'COURSE', ?, 50, now()) RETURNING id
                """, Long.class, courseId);

        Long questionId = jdbc.queryForObject("""
                INSERT INTO quiz_questions (text, order_index, hint_by_ai_enabled, quiz_id)
                VALUES ('The seeded question', 0, false, ?) RETURNING id
                """, Long.class, quizId);

        Long optionId = jdbc.queryForObject("""
                INSERT INTO quiz_options (text, is_correct, order_index, question_id)
                VALUES ('Chosen', true, 0, ?) RETURNING id
                """, Long.class, questionId);

        Long attemptId = jdbc.queryForObject("""
                INSERT INTO quiz_attempts (quiz_id, student_id, course_id, attempt_number, correct_count,
                                           total_questions, score, passing_score, passed, submitted_at)
                VALUES (?, ?, ?, 1, 1, 1, 100, 50, true, now()) RETURNING id
                """, Long.class, quizId, studentId, courseId);

        jdbc.update("""
                INSERT INTO quiz_attempt_answers (attempt_id, question_id, selected_option_id, is_correct)
                VALUES (?, ?, ?, true)
                """, attemptId, questionId, optionId);

        return new Seeded(attemptId, questionId, optionId);
    }
}
