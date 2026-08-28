package com.manara.backend.course.repository;

import com.manara.backend.course.model.CourseChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CourseChangeRepository extends JpaRepository<CourseChange, Long> {

    /**
     * Everything that happened to one course since a given instant — one indexed range scan over
     * {@code (course_id, occurred_at)}, and the only read of this table on any learner path.
     *
     * <p>Newest first so a caller keeping one row per entity keeps the most recent description of
     * it without sorting again.
     *
     * <p>{@code since} is the reader's own {@code enrolledAt}, resolved from their authenticated
     * session. A learner cannot ask for a window they did not live through, because they never
     * supply one.
     */
    @Query("""
            SELECT c FROM CourseChange c
            WHERE c.courseId = :courseId AND c.occurredAt > :since
            ORDER BY c.occurredAt DESC, c.id DESC
            """)
    List<CourseChange> findSince(@Param("courseId") Long courseId, @Param("since") LocalDateTime since);
}
