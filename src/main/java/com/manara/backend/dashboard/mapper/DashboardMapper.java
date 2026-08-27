package com.manara.backend.dashboard.mapper;

import com.manara.backend.course.model.Enrollment;
import com.manara.backend.dashboard.dto.CourseStatus;
import com.manara.backend.dashboard.dto.CourseViewResponse;
import org.springframework.stereotype.Component;

@Component
public class DashboardMapper {

    public CourseViewResponse toCourseViewResponse(Enrollment enrollment, int totalLessons, int completedLessons) {
        var course = enrollment.getCourse();
        int progress = totalLessons == 0
                ? 0
                : (int) Math.round((completedLessons * 100.0) / totalLessons);

        return CourseViewResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .instructor(course.getInstructor().getUser().getFullName())
                .description(course.getDescription())
                .image(course.getImage())
                .progress(progress)
                .totalLessons(totalLessons)
                .completedLessons(completedLessons)
                .status(resolveStatus(progress))
                .category(course.getSubtitle())
                .duration(formatDuration(course.getDuration()))
                // Read straight off the course the enrolment already points at, so the learner's
                // course list costs no extra query to answer "has this changed since I enrolled?".
                .hasUpdatesSincePublish(course.hasUpdatesSincePublish())
                .build();
    }

    private CourseStatus resolveStatus(int progress) {
        if (progress >= 100) return CourseStatus.COMPLETED;
        if (progress <= 0) return CourseStatus.NOT_STARTED;
        return CourseStatus.IN_PROGRESS;
    }

    private String formatDuration(Integer minutes) {
        if (minutes == null || minutes <= 0) return "0m";
        int hours = minutes / 60;
        int mins = minutes % 60;
        if (hours == 0) return mins + "m";
        if (mins == 0) return hours + "h";
        return hours + "h " + mins + "m";
    }
}
