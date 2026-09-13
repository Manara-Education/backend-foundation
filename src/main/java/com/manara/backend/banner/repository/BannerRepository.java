package com.manara.backend.banner.repository;

import com.manara.backend.banner.model.Banner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BannerRepository extends JpaRepository<Banner, Long> {

    /**
     * The owner's management list. Ordered the same way learners see it, so the numbers next to
     * each row and the order they run in are the same list.
     */
    @Query("""
            select b from Banner b
            where b.instructor.id = :instructorId
            order by b.priority asc, b.startAt asc nulls first, b.id asc
            """)
    List<Banner> findOwnedOrdered(@Param("instructorId") Long instructorId);

    Optional<Banner> findByIdAndInstructorId(Long id, Long instructorId);

    /**
     * Everything a learner may be shown right now: published, switched on, and inside its window.
     *
     * <p>An open end of the window is not a missing value — a banner with no start is already
     * running and one with no end runs until it is switched off, so both are matched rather than
     * excluded.
     */
    @Query("""
            select b from Banner b
            where b.draft = false
              and b.enabled = true
              and (b.startAt is null or b.startAt <= :now)
              and (b.endAt is null or b.endAt >= :now)
            order by b.priority asc, b.startAt asc nulls first, b.id asc
            """)
    List<Banner> findActive(@Param("now") LocalDateTime now);

    @Query("select coalesce(max(b.priority), 0) from Banner b where b.instructor.id = :instructorId")
    int findHighestPriority(@Param("instructorId") Long instructorId);

    /**
     * How many banners still show this image.
     *
     * <p>Banners never delete a file, but they hold references to uploads exactly as courses do —
     * which is how a banner's image could be destroyed by a course edit somewhere else entirely.
     * The retention rule counts them for that reason: a holder that is not counted is a holder
     * whose image can be deleted out from under it.
     *
     * <p>Deliberately unfiltered by draft, enabled or schedule. A banner that is switched off or
     * still being written is a reference all the same, and its owner would find the image gone the
     * moment they turned it on.
     */
    long countByImageUrl(String imageUrl);
}
