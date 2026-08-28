package com.manara.backend.course.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.repository.SubscriptionPlanRepository;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.service.QuizService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Loads a whole course in a fixed number of queries.
 *
 * <p>Everything is fetched breadth-first — all modules, then all lessons, then all quizzes of each
 * owner type, then the plans — instead of walking the tree and letting each node fetch its own
 * children. That keeps the cost independent of how many modules and lessons a course has, which is
 * what stops the editor endpoint from degrading into an N+1 as courses grow. Answer keys are
 * loaded once here and each caller decides which representation to expose.
 */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseAggregateLoader {

    private final CourseModuleRepository courseModuleRepository;
    private final LessonRepository lessonRepository;
    private final SubscriptionPlanRepository subscriptionPlanRepository;
    private final QuizService quizService;

    public CourseAggregate load(Course course) {
        boolean modular = course.getStructure() == CourseStructure.MODULES;

        List<CourseModule> modules = modular
                ? courseModuleRepository.findByCourseIdOrderByOrderIndexAsc(course.getId())
                : List.of();

        // Content of the inactive structure is filtered out here rather than in every mapper, so a
        // course that still holds leftovers can never surface them.
        List<Lesson> lessons = lessonRepository.findCourseLessonsInReadingOrder(course.getId()).stream()
                .filter(lesson -> modular == (lesson.getModule() != null))
                .toList();

        List<Long> lessonIds = lessons.stream().map(Lesson::getId).toList();
        List<Long> moduleIds = modules.stream().map(CourseModule::getId).toList();

        return new CourseAggregate(
                course,
                modules,
                lessons,
                quizService.findByOwners(QuizOwnerType.LESSON, lessonIds),
                quizService.findByOwners(QuizOwnerType.MODULE, moduleIds),
                quizService.findByOwner(QuizOwnerType.COURSE, course.getId()).orElse(null),
                subscriptionPlanRepository.findByCourseIdAndRetiredAtIsNullOrderByOrderIndexAsc(course.getId()));
    }
}
