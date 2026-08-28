package com.manara.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V11 does to a database that already has courses in it.
 *
 * <p>{@link FlywayMigrationTest} proves the migration runs against an empty container, which is the
 * easy half. The half that matters is a production {@code lessons} table full of rows written when
 * every lesson was a video, because there are two ways this release could go badly wrong for them
 * and neither is visible from reading the SQL:
 *
 * <ul>
 *   <li>a lesson could come out of the migration as something other than {@code VIDEO}, and a
 *       learner would open a lesson to find their video replaced by an empty article;
 *   <li>a stamp could move, and every enrolled learner on the platform would be told every lesson
 *       they own had changed on the morning of the deploy.
 * </ul>
 *
 * <p>These seed a course that predates the migration — with an enrollment, a completion and content
 * timestamps already in the past — and then check what the new columns did to it.
 */
class LessonContentTypeMigrationTest extends AbstractPostgresBackedTest {

    private static final LocalDateTime JANUARY = LocalDateTime.of(2026, 1, 10, 9, 0);
    private static final LocalDateTime FEBRUARY = LocalDateTime.of(2026, 2, 10, 9, 0);

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("every lesson that existed before the migration is a VIDEO lesson")
    void existingLessonsBecomeVideoLessons() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Legacy course");
            long youtube = seedLesson(courseId, "YouTube lesson",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0, 600);
            long vimeo = seedLesson(courseId, "Vimeo lesson", "https://vimeo.com/76979871", 1, 900);

            // Nothing is re-run here: the column's DEFAULT is what classifies these rows, and it did
            // so when the migration added it. There is deliberately no heuristic anywhere in V11 —
            // no inspection of description length, no guessing from the URL — so this is the whole
            // of the classification and it cannot get a lesson wrong.
            assertThat(contentTypeOf(youtube)).isEqualTo("VIDEO");
            assertThat(contentTypeOf(vimeo)).isEqualTo("VIDEO");

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("existing video data is untouched: URL, provider, thumbnail, duration and order")
    void preservesEveryExistingVideoColumn() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Video data");
            String url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
            long lessonId = seedLesson(courseId, "Lesson", url, 3, 2700);
            jdbc.update("""
                    UPDATE lessons SET video_provider = 'YOUTUBE', external_video_id = 'dQw4w9WgXcQ',
                                       video_thumbnail_url = 'https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg'
                    WHERE id = ?""", lessonId);

            Map<String, Object> row = jdbc.queryForMap("""
                    SELECT video_url, video_provider, external_video_id, video_thumbnail_url,
                           duration, order_index, rich_content
                    FROM lessons WHERE id = ?""", lessonId);

            assertThat(row.get("video_url")).isEqualTo(url);
            assertThat(row.get("video_provider")).isEqualTo("YOUTUBE");
            assertThat(row.get("external_video_id")).isEqualTo("dQw4w9WgXcQ");
            assertThat(row.get("video_thumbnail_url"))
                    .isEqualTo("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
            assertThat(row.get("duration")).isEqualTo(2700);
            assertThat(row.get("order_index")).isEqualTo(3);
            // The new column is empty for a lesson that has never had rich content. It is not
            // back-filled from `description`, which is a different field with a different meaning.
            assertThat(row.get("rich_content")).isNull();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("no content stamp moves, so nobody is told their course changed on deploy day")
    void movesNoContentTimestamp() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Stamped course");
            long lessonId = seedLesson(courseId, "Lesson",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0, 600);

            // The shape a real row is in the morning of the deploy: content last genuinely edited in
            // January, a learner enrolled in February. The badge rule is
            // `content_updated_at > enrolled_at`, so a migration that moved the stamp to now() would
            // make it true for every lesson on the platform at once.
            jdbc.update("UPDATE lessons SET content_updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(JANUARY), lessonId);
            jdbc.update("UPDATE courses SET content_updated_at = ? WHERE id = ?",
                    Timestamp.valueOf(JANUARY), courseId);
            long studentId = seedEnrollment(courseId, FEBRUARY);

            // V11's statements, re-run over the seeded rows. They are ALTERs, so this is really a
            // statement about what the migration does NOT contain — there is no UPDATE in it.
            jdbc.update("ALTER TABLE public.lessons ADD COLUMN IF NOT EXISTS content_type "
                    + "character varying(32) NOT NULL DEFAULT 'VIDEO'");
            jdbc.update("ALTER TABLE public.lessons ADD COLUMN IF NOT EXISTS rich_content text");

            assertThat(stampOf("lessons", lessonId)).isEqualTo(JANUARY);
            assertThat(stampOf("courses", courseId)).isEqualTo(JANUARY);
            assertThat(enrolledAtOf(courseId, studentId)).isEqualTo(FEBRUARY);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("enrollment, completion and progress survive the migration untouched")
    void preservesLearnerState() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Enrolled course");
            long lessonId = seedLesson(courseId, "Lesson",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0, 600);
            long studentId = seedEnrollment(courseId, FEBRUARY);
            jdbc.update("UPDATE enrollments SET progress = 50 WHERE course_id = ? AND student_id = ?",
                    courseId, studentId);
            jdbc.update("INSERT INTO completed_lessons (student_id, lesson_id, completed_at) "
                    + "VALUES (?, ?, ?)", studentId, lessonId, Timestamp.valueOf(FEBRUARY));

            assertThat(jdbc.queryForObject(
                    "SELECT progress FROM enrollments WHERE course_id = ? AND student_id = ?",
                    Integer.class, courseId, studentId)).isEqualTo(50);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM completed_lessons WHERE student_id = ? AND lesson_id = ?",
                    Integer.class, studentId, lessonId)).isEqualTo(1);
            // The lesson id itself is the thing every one of those rows points at, and it is
            // unchanged — the migration adds columns and moves nothing.
            assertThat(jdbc.queryForObject("SELECT count(*) FROM lessons WHERE id = ?",
                    Integer.class, lessonId)).isEqualTo(1);

            status.setRollbackOnly();
        });
    }

    // --- the constraints the migration installs -------------------------------

    @Test
    @DisplayName("a rich-content lesson may be stored with no video at all")
    void allowsARichContentLessonWithoutAVideo() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Rich course");

            // The point of relaxing video_url's NOT NULL. Under the old schema this INSERT was
            // impossible, which is the assumption the whole feature exists to remove.
            assertThatCode(() -> jdbc.update("""
                    INSERT INTO lessons (title, content_type, rich_content, order_index, duration,
                                         course_id, created_at, content_updated_at)
                    VALUES ('Article', 'RICH_CONTENT', '{"version":1,"blocks":[]}', 0, 0, ?, now(), now())
                    """, courseId))
                    .doesNotThrowAnyException();

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a VIDEO lesson still cannot be stored without a video")
    void stillRequiresAVideoForVideoLessons() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Guarded course");

            // What replaces the old NOT NULL. The guarantee is not weakened, only restated against
            // the type: "every video lesson has a video" rather than "every lesson has a video".
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO lessons (title, content_type, order_index, duration, course_id,
                                         created_at, content_updated_at)
                    VALUES ('Broken', 'VIDEO', 0, 0, ?, now(), now())
                    """, courseId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("a content type outside the enum is refused by the database")
    void refusesAnUnknownContentType() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Enum course");

            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO lessons (title, content_type, video_url, order_index, duration,
                                         course_id, created_at, content_updated_at)
                    VALUES ('Odd', 'PODCAST', 'https://example.test/v', 0, 0, ?, now(), now())
                    """, courseId))
                    .isInstanceOf(DataIntegrityViolationException.class);

            status.setRollbackOnly();
        });
    }

    @Test
    @DisplayName("an INSERT that never heard of content_type still produces a video lesson")
    void supportsAnInstanceRunningThePreviousBuild() {
        transactionTemplate.executeWithoutResult(status -> {
            long courseId = seedCourse("Rolling deploy");

            // Exactly the statement the previous build emits: it does not name content_type, so the
            // DEFAULT fills it. This is what lets both builds run against this schema at once.
            long lessonId = seedLesson(courseId, "From old build",
                    "https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0, 300);

            assertThat(contentTypeOf(lessonId)).isEqualTo("VIDEO");

            status.setRollbackOnly();
        });
    }

    // ── Reading ──────────────────────────────────────────────────────────────

    private String contentTypeOf(long lessonId) {
        return jdbc.queryForObject("SELECT content_type FROM lessons WHERE id = ?", String.class, lessonId);
    }

    private LocalDateTime stampOf(String table, long id) {
        Timestamp stamp = jdbc.queryForObject(
                "SELECT content_updated_at FROM " + table + " WHERE id = ?", Timestamp.class, id);
        return stamp == null ? null : stamp.toLocalDateTime();
    }

    private LocalDateTime enrolledAtOf(long courseId, long studentId) {
        Timestamp stamp = jdbc.queryForObject(
                "SELECT enrolled_at FROM enrollments WHERE course_id = ? AND student_id = ?",
                Timestamp.class, courseId, studentId);
        return stamp == null ? null : stamp.toLocalDateTime();
    }

    // ── Seeding ──────────────────────────────────────────────────────────────

    private long seedCourse(String title) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES (?, ?, 'x', true, false, 'INSTRUCTOR', now()) RETURNING id
                """, Long.class, title + " instructor",
                "content-type-migration-" + title.hashCode() + "@x.test");

        Long instructorId = jdbc.queryForObject(
                "INSERT INTO instructors (user_id) VALUES (?) RETURNING id", Long.class, userId);

        return jdbc.queryForObject("""
                INSERT INTO courses (title, description, status, structure, access_type,
                                     students_count, created_at, instructor_id, last_published_at)
                VALUES (?, 'seeded', 'PUBLISHED', 'FLAT', 'FREE', 0, ?, ?, ?) RETURNING id
                """, Long.class, title, Timestamp.valueOf(JANUARY), instructorId,
                Timestamp.valueOf(JANUARY));
    }

    /** A lesson written exactly as the build before this one wrote them: no content_type named. */
    private long seedLesson(long courseId, String title, String videoUrl, int orderIndex, int duration) {
        return jdbc.queryForObject("""
                INSERT INTO lessons (title, video_url, order_index, duration, course_id, created_at,
                                     content_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, title, videoUrl, orderIndex, duration, courseId,
                Timestamp.valueOf(JANUARY), Timestamp.valueOf(JANUARY));
    }

    private long seedEnrollment(long courseId, LocalDateTime enrolledAt) {
        Long userId = jdbc.queryForObject("""
                INSERT INTO users (full_name, email, password, email_verified, requires_password_reset,
                                   role, created_at)
                VALUES ('Learner', ?, 'x', true, false, 'STUDENT', now()) RETURNING id
                """, Long.class, "content-type-learner-" + courseId + "@x.test");

        Long studentId = jdbc.queryForObject(
                "INSERT INTO students (user_id) VALUES (?) RETURNING id", Long.class, userId);

        jdbc.update("""
                INSERT INTO enrollments (course_id, student_id, enrolled, enrolled_at, progress)
                VALUES (?, ?, true, ?, 0)
                """, courseId, studentId, Timestamp.valueOf(enrolledAt));

        return studentId;
    }
}
