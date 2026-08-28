package com.manara.backend.dashboard.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseViewResponse {
    private Long id;
    private String title;
    private String instructor;
    private String description;
    private String image;
    private Integer progress;
    private Integer totalLessons;
    private Integer completedLessons;
    private CourseStatus status;
    private String category;
    private String duration;

    /**
     * Whether the instructor has edited this course since they last published it.
     *
     * <p>The same value for every card of this course on every learner's screen, because it is a
     * statement about the instructor's workflow. Kept so clients written against the previous
     * contract keep working.
     *
     * @deprecated for learner-facing use. A card belongs to one learner and should show
     * {@link #hasUpdatesSinceEnrollment}, which answers their question rather than the author's.
     */
    @Deprecated
    private Boolean hasUpdatesSincePublish;

    /**
     * Whether this course has changed since <em>this learner</em> enrolled.
     *
     * <pre>{@code course.contentUpdatedAt > enrollment.enrolledAt}</pre>
     *
     * <p>What the "Updated" badge on the card reads. Two students of the same course get different
     * answers on their own dashboards, which is the entire point: the one who enrolled this morning
     * bought the version that already contained everything.
     *
     * <p>Costs nothing extra to answer — both halves of the comparison are already loaded with the
     * enrollment this card was built from, so the learner's course list makes no additional query.
     * Which lesson changed is a question for the course-details screen, not for a list of cards.
     */
    private Boolean hasUpdatesSinceEnrollment;
}
