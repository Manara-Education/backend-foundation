package com.manara.backend.course.repository;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.SubscriptionPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Every read the anonymous catalogue makes, and nothing else.
 *
 * <p>A repository of its own, rather than three more methods on {@link CourseRepository}, so that
 * what an unauthenticated caller can reach is one short file to audit. Every course query here
 * carries the discovery rule — {@code PUBLISHED} and {@code PUBLIC}, as
 * {@code Course.isDiscoverable()} states it — in its {@code WHERE} clause. There is no method on this
 * interface that can return a draft or a private course, so no caller can forget to filter one out,
 * and nothing counted or paginated on top of these queries can see one either.
 *
 * <p>Read-only by construction: it extends the bare {@code Repository} marker, so it has no
 * {@code save}, {@code delete} or unscoped {@code findById} for anyone to reach for.
 */
@Repository
public interface PublicCourseRepository extends org.springframework.data.repository.Repository<Course, Long> {

    /**
     * One page of discoverable courses, newest first.
     *
     * <p>The instructor and their account are fetched with the page because every card prints the
     * instructor's name; without the join each row cost two more selects. Both joins are to-one, so
     * the database applies the page limit itself rather than Hibernate paging in memory. The count
     * query uses the same predicate and no joins, so the total is exactly the eligible courses.
     *
     * <p>The order is fixed here and not taken from the caller: {@code id} descending is unique, so
     * pages are stable and no course appears on two of them.
     */
    @Query(value = """
            select c from Course c join fetch c.instructor i join fetch i.user
            where c.status = com.manara.backend.course.model.CourseStatus.PUBLISHED
              and c.visibility = com.manara.backend.course.model.CourseVisibility.PUBLIC
            order by c.id desc
            """,
            countQuery = """
            select count(c) from Course c
            where c.status = com.manara.backend.course.model.CourseStatus.PUBLISHED
              and c.visibility = com.manara.backend.course.model.CourseVisibility.PUBLIC
            """)
    Page<Course> findDiscoverablePage(Pageable pageable);

    /**
     * One course, if and only if it is discoverable.
     *
     * <p>The rule is in the query rather than checked after loading, so a draft, a private course
     * and an id that does not exist are the same empty result. The caller cannot tell them apart,
     * and so cannot tell a visitor.
     */
    @Query("""
            select c from Course c join fetch c.instructor i join fetch i.user
            where c.id = :courseId
              and c.status = com.manara.backend.course.model.CourseStatus.PUBLISHED
              and c.visibility = com.manara.backend.course.model.CourseVisibility.PUBLIC
            """)
    Optional<Course> findDiscoverableById(@Param("courseId") Long courseId);

    /**
     * The plans still on offer for a set of courses, in one query.
     *
     * <p>Retired plans are excluded here, exactly as every other offer read excludes them. The
     * caller passes only ids it has already loaded through the discoverable queries above, so this
     * never reveals anything about a course the catalogue does not show.
     */
    @Query("""
            select p from SubscriptionPlan p
            where p.course.id in :courseIds
              and p.retiredAt is null
            order by p.course.id, p.orderIndex, p.id
            """)
    List<SubscriptionPlan> findActivePlansOfCourses(@Param("courseIds") Collection<Long> courseIds);
}
