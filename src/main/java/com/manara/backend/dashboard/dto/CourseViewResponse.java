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
}
