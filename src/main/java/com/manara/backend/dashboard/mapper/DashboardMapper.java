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
                .hasUpdatesSincePublish(course.hasUpdatesSincePublish())
                // The learner's own answer, from the enrollment this card was built from. Asked of
                // the enrollment rather than computed here, so this screen and the course-details
                // screen are reading one implementation of the rule.
                .hasUpdatesSinceEnrollment(enrollment.hasCourseUpdates())
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
