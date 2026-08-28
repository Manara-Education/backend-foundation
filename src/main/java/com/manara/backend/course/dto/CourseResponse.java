package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.model.CourseVisibility;
import com.manara.backend.lesson.dto.LessonResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Summary shape used by the course list endpoints. The new fields are additive — {@code price} is
 * still populated from the course's purchase price so existing clients keep working.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseResponse {
    private Long id;
    private String title;
    private String subtitle;
    private String image;
    private String description;
    private Integer duration;
    private Integer lessonCount;
    private BigDecimal price;
    private BigDecimal purchasePrice;
    private CourseAccessType accessType;
    private CourseStructure structure;
    private CourseStatus status;

    /**
     * Who the course is offered to, alongside — never instead of — {@code status}.
     *
     * <p>Additive, and the two are read together: a course can be {@code PUBLISHED} and
     * {@code PRIVATE} at once, so a client that collapses them into one badge is describing
     * something the domain does not have. A client written before this field existed simply ignores
     * it and keeps rendering publication exactly as it did.
     */
    private CourseVisibility visibility;

    /**
     * Whether the course has changed in a way its learners should be told about.
     *
     * <p>The server's answer, derived from the publication baseline and the content version, so
     * every screen that shows an "Updated" badge shows the same thing. Deliberately not a pair of
     * timestamps for clients to compare: two clients comparing them would eventually disagree, and
     * the rule ("published, and edited since it was last published") belongs in one place.
     *
     * <p>False for a draft, false for a course that was never published, and false for every course
     * that already existed when this was introduced.
     */
    private Boolean hasUpdatesSincePublish;

    private Integer studentsCount;
    private Long instructorId;
    private String instructorName;
    private LocalDateTime createdAt;

    /**
     * Last write of any kind, which is what the instructor card's "آخر تحديث" line prints.
     *
     * <p>Added because the card already read it and the list payload never carried it, so every row
     * silently fell back to its creation date. Not the same thing as the update badge — this moves
     * for a purchase or a background video lookup too, which is exactly why the badge is derived
     * from its own timestamps instead.
     */
    private LocalDateTime updatedAt;

    private List<LessonResponse> lessons;
}
