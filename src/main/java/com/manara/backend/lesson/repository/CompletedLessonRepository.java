package com.manara.backend.lesson.repository;

import com.manara.backend.lesson.model.CompletedLesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CompletedLessonRepository extends JpaRepository<CompletedLesson, Long> {
    Optional<CompletedLesson> findByStudentIdAndLessonId(Long studentId, Long lessonId);

    int countByStudentIdAndLesson_Course_Id(Long studentId, Long courseId);

    @Query("select cl.lesson.id from CompletedLesson cl where cl.student.id = :studentId and cl.lesson.course.id = :courseId")
    List<Long> findCompletedLessonIdsByStudentIdAndCourseId(@Param("studentId") Long studentId, @Param("courseId") Long courseId);

    @Modifying
    @Query("delete from CompletedLesson cl where cl.lesson.id = :lessonId")
    void deleteByLessonId(@Param("lessonId") Long lessonId);

    /**
     * Used when a course update removes several lessons at once — deleting the lessons without
     * clearing their progress rows would violate the foreign key.
     *
     * <p>Flushes first so edits made earlier in the same synchronization pass are already written,
     * but deliberately does not clear the persistence context: a course update still holds the
     * course, its modules and its surviving lessons as managed entities, and detaching them
     * mid-pass would strand every change made after this point.
     */
    @Modifying(flushAutomatically = true)
    @Query("delete from CompletedLesson cl where cl.lesson.id in :lessonIds")
    void deleteByLessonIdIn(@Param("lessonIds") List<Long> lessonIds);

    /**
     * Records a completion, or does nothing because it is already recorded.
     *
     * <p>Native and {@code ON CONFLICT DO NOTHING} for one reason: idempotency has to survive two
     * requests arriving at once, and nothing above the database can make it. A read followed by a
     * {@code save} is a check-then-act — two transactions both read "not completed", both insert,
     * and the unique index turns the loser into a {@code 500} for a learner whose lesson is by then
     * demonstrably complete.
     *
     * <p>Catching that violation instead would be worse than it looks. A constraint failure raised
     * during a flush leaves the persistence context poisoned and the transaction rollback-only, so
     * the enrollment progress written immediately afterwards would be lost at commit — the learner
     * would be told the lesson was complete while their course progress silently was not. Letting
     * the conflict be resolved inside the statement means there is no failed flush to recover from.
     *
     * <p>The existing row is kept rather than updated: a learner completed the lesson once, at the
     * first moment they said so, however many times the click was delivered.
     *
     * @return 1 when this call created the row, 0 when it already existed
     */
    @Modifying
    @Query(value = """
            INSERT INTO completed_lessons (student_id, lesson_id, completed_at)
            VALUES (:studentId, :lessonId, :completedAt)
            ON CONFLICT (student_id, lesson_id) DO NOTHING
            """, nativeQuery = true)
    int recordCompletion(@Param("studentId") Long studentId,
                         @Param("lessonId") Long lessonId,
                         @Param("completedAt") LocalDateTime completedAt);
}
