package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.course.service.ResolvedCourseSettings;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Course-level mapping: summaries, enrolments and the course entity itself. The full editor and
 * learner trees are assembled by {@link CourseAggregateMapper}; the access-lifecycle rows by
 * {@link EntitlementMapper}.
 */
@Component
public class CourseMapper {

    public Course toCourse(CourseRequest request, Instructor instructor, ResolvedCourseSettings settings) {
        return Course.builder()
                .title(request.getTitle().trim())
                .subtitle(request.getSubtitle())
                .image(request.getImage())
                .description(request.getDescription())
                .duration(request.getDuration())
                .structure(settings.structure())
                .status(settings.status())
                .accessType(settings.accessType())
                .purchasePrice(settings.purchasePrice())
                .instructor(instructor)
                .build();
    }

    public Enrollment toEnrollment(Course course, Student student) {
        return Enrollment.builder()
                .course(course)
                .student(student)
                .build();
    }

    public CourseResponse toCourseResponse(Course course) {
        return toCourseResponse(course, null);
    }

    public CourseResponse toCourseResponse(Course course, List<LessonResponse> lessons) {
        return CourseResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .image(course.getImage())
                .description(course.getDescription())
                .duration(course.getDuration())
                .lessonCount(course.getLessonCount())
                // Both names carry the same value: `price` for clients written against the previous
                // contract, `purchasePrice` for the current one.
                .price(course.getPurchasePrice())
                .purchasePrice(course.getPurchasePrice())
                .accessType(course.getAccessType())
                .structure(course.getStructure())
                .status(course.getStatus())
                .hasUpdatesSincePublish(course.hasUpdatesSincePublish())
                .studentsCount(course.getStudentsCount())
                .instructorId(course.getInstructor().getId())
                .instructorName(course.getInstructor().getUser().getFullName())
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .lessons(lessons)
                .build();
    }
}
