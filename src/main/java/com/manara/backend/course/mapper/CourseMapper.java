package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.dto.LessonResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class CourseMapper {

    private final LessonMapper lessonMapper;

    public Course toCourse(CourseRequest request, Instructor instructor) {
        return Course.builder()
                .title(request.getTitle())
                .subtitle(request.getSubtitle())
                .image(request.getImage())
                .description(request.getDescription())
                .duration(request.getDuration())
                .price(request.getPrice())
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
                .price(course.getPrice())
                .studentsCount(course.getStudentsCount())
                .instructorId(course.getInstructor().getId())
                .instructorName(course.getInstructor().getUser().getFullName())
                .createdAt(course.getCreatedAt())
                .lessons(lessons)
                .build();
    }

    public CourseDetailsResponse toCourseDetailsResponse(Course course, List<LessonResponse> lessons) {
        var instructor = course.getInstructor();
        var instructorUser = instructor.getUser();

        int totalDuration = lessons == null ? 0 : lessons.stream()
                .map(LessonResponse::getDuration)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        var courseInfo = CourseDetailsResponse.CourseInfo.builder()
                .id(course.getId())
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .image(course.getImage())
                .description(course.getDescription())
                .duration(totalDuration)
                .lessonCount(course.getLessonCount())
                .price(course.getPrice())
                .studentsCount(course.getStudentsCount())
                .createdAt(course.getCreatedAt())
                .build();

        var instructorInfo = CourseDetailsResponse.InstructorInfo.builder()
                .id(instructor.getId())
                .fullName(instructorUser.getFullName())
                .email(instructorUser.getEmail())
                .bio(instructor.getBio())
                .specialization(instructor.getSpecialization())
                .build();

        return CourseDetailsResponse.builder()
                .course(courseInfo)
                .instructor(instructorInfo)
                .lessons(lessons)
                .build();
    }

    public EnrollmentResponse toEnrollmentResponse(Enrollment enrollment) {
        return EnrollmentResponse.builder()
                .id(enrollment.getId())
                .course(toCourseResponse(enrollment.getCourse()))
                .progress(enrollment.getProgress())
                .enrolled(enrollment.getEnrolled())
                .enrolledAt(enrollment.getEnrolledAt())
                .build();
    }
}
