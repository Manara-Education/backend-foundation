package com.manara.backend.course.mapper;

import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.EnrollmentResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Enrollment;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.model.Student;
import org.springframework.stereotype.Component;

@Component
public class CourseMapper {

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
        return CourseResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .image(course.getImage())
                .description(course.getDescription())
                .duration(course.getDuration())
                .price(course.getPrice())
                .studentsCount(course.getStudentsCount())
                .instructorName(course.getInstructor().getUser().getFullName())
                .createdAt(course.getCreatedAt())
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
