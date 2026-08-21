package com.manara.backend.course.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.InstructorModuleResponse;
import com.manara.backend.course.dto.LearnerModuleResponse;
import com.manara.backend.course.dto.SubscriptionPlanResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.mapper.QuizMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns a loaded {@link CourseAggregate} into one of the two course trees the API serves.
 *
 * <p>The split is the security boundary for answer keys: {@link #toInstructorCourseResponse} maps
 * quizzes through {@code QuizMapper#toInstructorResponse}, {@link #toCourseDetailsResponse} through
 * {@code toLearnerResponse}, whose result type has no field for a correct answer. Both walk the
 * same aggregate, so there is one tree-building implementation, not two.
 */
@Component
@RequiredArgsConstructor
public class CourseAggregateMapper {

    private final LessonMapper lessonMapper;
    private final QuizMapper quizMapper;
    private final SubscriptionPlanMapper subscriptionPlanMapper;
    private final DurationFormatter durationFormatter;

    public InstructorCourseResponse toInstructorCourseResponse(CourseAggregate aggregate) {
        Course course = aggregate.course();
        boolean flat = isFlat(course);

        return InstructorCourseResponse.builder()
                .id(course.getId())
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .image(course.getImage())
                .description(course.getDescription())
                .duration(course.getDuration())
                .lessonCount(aggregate.lessons().size())
                .studentsCount(course.getStudentsCount())
                .instructorId(course.getInstructor().getId())
                .instructorName(course.getInstructor().getUser().getFullName())
                .structure(course.getStructure())
                .status(course.getStatus())
                // A module course reaches its lessons through its modules; the flat branch stays
                // empty so no response ever describes a course as being both shapes at once.
                .lessons(flat ? instructorLessons(aggregate, aggregate.lessons()) : List.of())
                .modules(aggregate.modules().stream()
                        .map(module -> toInstructorModuleResponse(aggregate, module))
                        .toList())
                .finalQuiz(quizMapper.toInstructorResponse(aggregate.finalQuiz()))
                .accessType(course.getAccessType())
                .purchasePrice(course.getPurchasePrice())
                .price(course.getPurchasePrice())
                .subscriptionPlans(planResponses(aggregate))
                .createdAt(course.getCreatedAt())
                .updatedAt(course.getUpdatedAt())
                .build();
    }

    /**
     * @param completedLessonIds ids the learner has completed, or {@code null} when the view does
     *                           not carry completion state (course discovery)
     */
    public CourseDetailsResponse toCourseDetailsResponse(CourseAggregate aggregate, Set<Long> completedLessonIds) {
        Course course = aggregate.course();
        var instructor = course.getInstructor();
        var instructorUser = instructor.getUser();

        int totalDurationSeconds = sumDuration(aggregate.lessons());
        int remainingDurationSeconds = sumDuration(aggregate.lessons().stream()
                .filter(lesson -> completedLessonIds == null || !completedLessonIds.contains(lesson.getId()))
                .toList());

        var courseInfo = CourseDetailsResponse.CourseInfo.builder()
                .id(course.getId())
                .title(course.getTitle())
                .subtitle(course.getSubtitle())
                .image(course.getImage())
                .description(course.getDescription())
                .duration(durationFormatter.formatSeconds(totalDurationSeconds))
                .remainingDuration(durationFormatter.formatSeconds(remainingDurationSeconds))
                .lessonCount(aggregate.lessons().size())
                .price(course.getPurchasePrice())
                .purchasePrice(course.getPurchasePrice())
                .accessType(course.getAccessType())
                .subscriptionPlans(planResponses(aggregate))
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
                .structure(course.getStructure())
                .lessons(isFlat(course)
                        ? learnerLessons(aggregate, aggregate.lessons(), completedLessonIds)
                        : List.of())
                .modules(aggregate.modules().stream()
                        .map(module -> toLearnerModuleResponse(aggregate, module, completedLessonIds))
                        .toList())
                .finalQuiz(quizMapper.toLearnerResponse(aggregate.finalQuiz()))
                .build();
    }

    private InstructorModuleResponse toInstructorModuleResponse(CourseAggregate aggregate, CourseModule module) {
        return InstructorModuleResponse.builder()
                .id(module.getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .orderIndex(module.getOrderIndex())
                .lessons(instructorLessons(aggregate, aggregate.lessonsOf(module)))
                .quiz(quizMapper.toInstructorResponse(aggregate.quizOfModule(module)))
                .build();
    }

    private LearnerModuleResponse toLearnerModuleResponse(CourseAggregate aggregate, CourseModule module,
                                                          Set<Long> completedLessonIds) {
        return LearnerModuleResponse.builder()
                .id(module.getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .orderIndex(module.getOrderIndex())
                .lessons(learnerLessons(aggregate, aggregate.lessonsOf(module), completedLessonIds))
                .quiz(quizMapper.toLearnerResponse(aggregate.quizOfModule(module)))
                .build();
    }

    private List<InstructorLessonResponse> instructorLessons(CourseAggregate aggregate, List<Lesson> lessons) {
        return lessons.stream()
                .map(lesson -> lessonMapper.toInstructorLessonResponse(
                        lesson, quizMapper.toInstructorResponse(aggregate.quizOfLesson(lesson))))
                .toList();
    }

    private List<LessonResponse> learnerLessons(CourseAggregate aggregate, List<Lesson> lessons,
                                                Set<Long> completedLessonIds) {
        return lessons.stream()
                .map(lesson -> lessonMapper.toLessonResponse(
                        lesson,
                        completedLessonIds == null ? null : completedLessonIds.contains(lesson.getId()),
                        quizMapper.toLearnerResponse(aggregate.quizOfLesson(lesson))))
                .toList();
    }

    private List<SubscriptionPlanResponse> planResponses(CourseAggregate aggregate) {
        return aggregate.subscriptionPlans().stream()
                .map(subscriptionPlanMapper::toSubscriptionPlanResponse)
                .toList();
    }

    private boolean isFlat(Course course) {
        return course.getStructure() != CourseStructure.MODULES;
    }

    private int sumDuration(List<Lesson> lessons) {
        return lessons.stream()
                .map(Lesson::getDuration)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }
}
