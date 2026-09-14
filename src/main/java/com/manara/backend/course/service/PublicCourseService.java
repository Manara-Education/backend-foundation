package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.dto.PublicCourseDetailResponse;
import com.manara.backend.course.dto.PublicCoursePageResponse;
import com.manara.backend.course.dto.PublicCourseSummaryResponse;
import com.manara.backend.course.mapper.PublicCourseMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.course.repository.PublicCourseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The anonymous catalogue: which courses anybody may see, and what each one costs.
 *
 * <p>Two rules hold for everything here, and neither is this class's to decide.
 *
 * <ul>
 *   <li><strong>Eligibility</strong> comes from {@link PublicCourseRepository}, whose every query is
 *       limited to published, public courses. Nothing is loaded and then filtered, so a draft or a
 *       private course never enters the result, the count or a page.
 *   <li><strong>Price</strong> comes from {@link PublicOffer}, which applies the rule checkout
 *       charges by. The list and the detail call it the same way on the same rows, so a course
 *       costs the same on both.
 * </ul>
 *
 * <p>Nothing here knows who is asking. The same request gets the same answer signed in or not,
 * which is what makes it safe to serve to nobody in particular.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicCourseService {

    /** The page size when none is asked for. */
    public static final int DEFAULT_PAGE_SIZE = 12;

    /** The largest page anybody may ask for. The list is anonymous, so its cost has to be bounded. */
    public static final int MAX_PAGE_SIZE = 50;

    private final PublicCourseRepository publicCourseRepository;
    private final PublicCourseMapper publicCourseMapper;

    /**
     * One page of the catalogue.
     *
     * <p>Out-of-range paging is refused with a {@code 400} rather than clamped, so a client that
     * asked for 500 learns that it received at most 50. The offset has to fit the database's page
     * arithmetic too, so a page number large enough to overflow it is refused the same way instead of
     * reaching the query. A page past the last one is simply empty, with the real totals.
     */
    public PublicCoursePageResponse listCourses(int page, int size) {
        if (page < 0 || (long) page * size > Integer.MAX_VALUE) {
            throw new BusinessException("error.request.parameterInvalid", "page");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("error.request.parameterInvalid", "size");
        }

        Page<Course> courses = publicCourseRepository.findDiscoverablePage(PageRequest.of(page, size));
        Map<Long, List<SubscriptionPlan>> plans = activePlansOf(courses.getContent());

        List<PublicCourseSummaryResponse> items = courses.getContent().stream()
                .map(course -> publicCourseMapper.toSummary(course, offerOf(course, plans)))
                .toList();
        return publicCourseMapper.toPage(items, page, size, courses.getTotalElements());
    }

    /**
     * One course, if it is discoverable.
     *
     * <p>A draft, a private course and an id that was never used are the same {@code 404}, with the
     * same body: the repository cannot tell them apart, so neither can this.
     */
    public PublicCourseDetailResponse getCourse(Long courseId) {
        Course course = publicCourseRepository.findDiscoverableById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", String.valueOf(courseId)));
        return publicCourseMapper.toDetail(course, offerOf(course, activePlansOf(List.of(course))));
    }

    private static PublicOffer offerOf(Course course, Map<Long, List<SubscriptionPlan>> plans) {
        return PublicOffer.of(course, plans.getOrDefault(course.getId(), List.of()));
    }

    /**
     * The active plans of whichever of these courses are sold by subscription, in one query.
     *
     * <p>Only subscription courses are asked about. A plan left over on a course since switched to
     * another access type is not part of its offer, so there is no reason to load it.
     */
    private Map<Long, List<SubscriptionPlan>> activePlansOf(List<Course> courses) {
        List<Long> subscriptionCourseIds = courses.stream()
                .filter(course -> course.getAccessType() == CourseAccessType.SUBSCRIPTION)
                .map(Course::getId)
                .toList();
        if (subscriptionCourseIds.isEmpty()) {
            return Map.of();
        }
        return publicCourseRepository.findActivePlansOfCourses(subscriptionCourseIds).stream()
                .collect(Collectors.groupingBy(plan -> plan.getCourse().getId()));
    }
}
