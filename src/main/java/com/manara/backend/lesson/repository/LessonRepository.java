package com.manara.backend.lesson.repository;

import com.manara.backend.lesson.model.Lesson;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, Long> {

    List<Lesson> findByCourseIdOrderByOrderIndexAsc(Long courseId);

    /**
     * Course-wide reading order for both structures: modules first in their own order, then lessons
     * in theirs. Flat courses have no module, so {@code coalesce} keeps them on a single level and
     * the result is identical to ordering by {@code orderIndex} alone.
     *
     * <p>The trailing {@code l.id} keeps the order total, so two lessons sharing an order index
     * never swap places between requests.
     */
    @Query("""
            select l from Lesson l
            left join fetch l.module m
            where l.course.id = :courseId
            order by coalesce(m.orderIndex, 0) asc, l.orderIndex asc, l.id asc
            """)
    List<Lesson> findCourseLessonsInReadingOrder(@Param("courseId") Long courseId);

    int countByCourseId(Long courseId);

    /** Lessons currently active in a {@code FLAT} course. */
    int countByCourseIdAndModuleIsNull(Long courseId);

    /** Lessons currently active in a {@code MODULES} course. */
    int countByCourseIdAndModuleIsNotNull(Long courseId);

    @Query("SELECT COALESCE(SUM(l.duration), 0) FROM Lesson l WHERE l.course.id = :courseId")
    Integer sumDurationByCourseId(@Param("courseId") Long courseId);

    /**
     * The root lessons of a course, in curriculum order.
     *
     * <p>{@code order_index} is unique per scope from V6 onwards, so the {@code id} tiebreaker
     * keeps the order total for rows written by an application version that predates it.
     */
    @Query("select l from Lesson l where l.course.id = :courseId and l.module is null "
            + "order by l.orderIndex asc, l.id asc")
    List<Lesson> findRootLessons(@Param("courseId") Long courseId);

    /** One module's lessons, in curriculum order. */
    @Query("select l from Lesson l where l.course.id = :courseId and l.module.id = :moduleId "
            + "order by l.orderIndex asc, l.id asc")
    List<Lesson> findModuleLessons(@Param("courseId") Long courseId, @Param("moduleId") Long moduleId);

    /**
     * The root lessons of a course, locked for the rest of the transaction.
     *
     * <p>The lock is what makes a reorder a reorder rather than a race. Two drags of the same
     * scope arriving together would otherwise each read the pre-drag list, and the order that
     * survived would be a mix of both — an arrangement neither instructor asked for. Held until
     * commit, so the second request reads the first one's result and either agrees with it or is
     * rejected as stale by the completeness check.
     *
     * <p>Scoped to lessons with no module: in a {@code FLAT} course these are all of them, and in
     * a {@code MODULES} course this is empty, which is what makes reordering root lessons of a
     * modular course a rejection rather than a silent no-op.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lesson l where l.course.id = :courseId and l.module is null "
            + "order by l.orderIndex asc, l.id asc")
    List<Lesson> findRootLessonsForUpdate(@Param("courseId") Long courseId);

    /**
     * One module's lessons, locked for the rest of the transaction.
     *
     * <p>Matched on the course as well as the module deliberately. A module id from another
     * instructor's course cannot select rows here, so a mismatched pair is an empty scope rather
     * than a foothold in someone else's content — the same "resolved against this course's own
     * children only" rule the aggregate synchronizer follows.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from Lesson l where l.course.id = :courseId and l.module.id = :moduleId "
            + "order by l.orderIndex asc, l.id asc")
    List<Lesson> findModuleLessonsForUpdate(@Param("courseId") Long courseId,
                                            @Param("moduleId") Long moduleId);
}
