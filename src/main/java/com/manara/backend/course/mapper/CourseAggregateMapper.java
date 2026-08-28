package com.manara.backend.course.mapper;

import com.manara.backend.common.util.DurationFormatter;
import com.manara.backend.course.dto.CourseAccessResponse;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.InstructorModuleResponse;
import com.manara.backend.course.dto.LearnerModuleResponse;
import com.manara.backend.course.dto.SubscriptionPlanResponse;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.service.CourseAggregate;
import com.manara.backend.course.service.CourseProgression;
import com.manara.backend.course.service.CourseUpdateWindow;
import com.manara.backend.lesson.dto.InstructorLessonResponse;
import com.manara.backend.lesson.dto.LessonResponse;
import com.manara.backend.lesson.mapper.LessonMapper;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.mapper.QuizMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

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
                .hasUpdatesSincePublish(course.hasUpdatesSincePublish())
                // What the next save has to quote back. Read and write both answer with it, so the
                // editor is never holding a revision the server has already moved past.
                .revision(course.getRevision())
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
     * @param progression what the viewing learner has reached — it decides which lessons are served
     *                    with their content and which are served as locked rows
     * @param access      the viewer's standing on the course: enrolled, entitled, and until when.
     *                    Kept beside the content rather than inferred from it, because "every lesson
     *                    is locked" is what an unenrolled visitor and a lapsed subscriber have in
     *                    common, and the screen has to tell them apart
     * @param updates     what has changed since the viewer enrolled. Asked once per row and it
     *                    answers from data already loaded, so a hundred-lesson curriculum costs the
     *                    same to annotate as a three-lesson one
     */
    public CourseDetailsResponse toCourseDetailsResponse(
            CourseAggregate aggregate, CourseProgression progression, CourseAccessResponse access,
            CourseUpdateWindow updates) {
        Course course = aggregate.course();
        var instructor = course.getInstructor();
        var instructorUser = instructor.getUser();

        int totalDurationSeconds = sumDuration(aggregate.lessons());
        int remainingDurationSeconds = sumDuration(aggregate.lessons().stream()
                .filter(lesson -> !Boolean.TRUE.equals(progression.completionOf(lesson)))
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
                .hasUpdatesSincePublish(course.hasUpdatesSincePublish())
                .hasUpdatesSinceEnrollment(updates.hasUpdatesSinceEnrollment())
                .latestContentUpdateAt(updates.latestContentUpdateAt())
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
                .access(access)
                .structure(course.getStructure())
                .lessons(isFlat(course)
                        ? learnerLessons(aggregate, aggregate.lessons(), progression, updates, null)
                        : List.of())
                .modules(aggregate.modules().stream()
                        .map(module -> toLearnerModuleResponse(aggregate, module, progression, updates))
                        .toList())
                .finalQuiz(quizMapper.toLearnerResponse(
                        aggregate.finalQuiz(), progression.stateOf(aggregate.finalQuiz()),
                        updates.describe(aggregate.finalQuiz())))
                .progress(progression.tracksProgress() ? progression.progress() : null)
                .courseCompleted(progression.tracksProgress() ? progression.courseCompleted() : null)
                .nextLessonId(progression.tracksProgress() ? progression.nextLessonId() : null)
                .removedContent(updates.removedContent())
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
                                                          CourseProgression progression,
                                                          CourseUpdateWindow updates) {
        return LearnerModuleResponse.builder()
                .id(module.getId())
                .title(module.getTitle())
                .description(module.getDescription())
                .orderIndex(module.getOrderIndex())
                // The module's own title is what a lesson moved into it is described as moving to.
                .lessons(learnerLessons(aggregate, aggregate.lessonsOf(module), progression, updates,
                        module.getTitle()))
                .quiz(quizMapper.toLearnerResponse(
                        aggregate.quizOfModule(module), progression.stateOf(aggregate.quizOfModule(module)),
                        updates.describe(aggregate.quizOfModule(module))))
                .locked(!progression.isModuleUnlocked(module.getId()))
                .change(updates.describe(module))
                .build();
    }

    private List<InstructorLessonResponse> instructorLessons(CourseAggregate aggregate, List<Lesson> lessons) {
        return lessons.stream()
                .map(lesson -> lessonMapper.toInstructorLessonResponse(
                        lesson, quizMapper.toInstructorResponse(aggregate.quizOfLesson(lesson))))
                .toList();
    }

    /**
     * The point where a curriculum listing stops being a content feed. A lesson the viewer has not
     * reached is still listed — that is the locked row the learner sees — but it is mapped through
     * the locked builder, so its video and its quiz never enter the response at all.
     */
    private List<LessonResponse> learnerLessons(CourseAggregate aggregate, List<Lesson> lessons,
                                                CourseProgression progression, CourseUpdateWindow updates,
                                                String parentLabel) {
        return lessons.stream()
                .map(lesson -> progression.isLessonAccessible(lesson)
                        ? lessonMapper.toLessonResponse(
                                lesson,
                                progression.completionOf(lesson),
                                quizMapper.toLearnerResponse(
                                        aggregate.quizOfLesson(lesson),
                                        progression.stateOf(aggregate.quizOfLesson(lesson)),
                                        updates.describe(aggregate.quizOfLesson(lesson))),
                                updates.describe(lesson, parentLabel))
                        : lessonMapper.toLockedLessonResponse(lesson, progression.completionOf(lesson),
                                updates.describe(lesson, parentLabel)))
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
