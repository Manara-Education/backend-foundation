package com.manara.backend.course.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.SubscriptionPlan;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.quiz.model.Quiz;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * A fully loaded course, ready to be turned into either the editor or the learner response.
 *
 * <p>Only content belonging to the course's active structure is present: a flat course carries no
 * modules and only unparented lessons, a module course only lessons that sit under a module. That
 * filtering happens once, here, so no response can accidentally mix the two.
 *
 * <p>Collections arrive in stored order and are handed on in that order.
 */
public record CourseAggregate(
        Course course,
        List<CourseModule> modules,
        List<Lesson> lessons,
        Map<Long, Quiz> lessonQuizzes,
        Map<Long, Quiz> moduleQuizzes,
        Quiz finalQuiz,
        List<SubscriptionPlan> subscriptionPlans) {

    /** Lessons of one module, in reading order. */
    public List<Lesson> lessonsOf(CourseModule module) {
        return lessons.stream()
                .filter(lesson -> lesson.getModule() != null
                        && Objects.equals(lesson.getModule().getId(), module.getId()))
                .toList();
    }

    public Quiz quizOfLesson(Lesson lesson) {
        return lessonQuizzes.get(lesson.getId());
    }

    public Quiz quizOfModule(CourseModule module) {
        return moduleQuizzes.get(module.getId());
    }

    /**
     * Every quiz this course owns — lesson quizzes, module exams and the final exam — flattened.
     *
     * <p>Quiz ownership is polymorphic, so there is no query that reaches a course's quizzes
     * directly. Walking the loaded aggregate is what establishes that a quiz belongs to this
     * course, which is the check a submission is authorized against.
     */
    public List<Quiz> allQuizzes() {
        return Stream.of(lessonQuizzes.values().stream(), moduleQuizzes.values().stream(), Stream.ofNullable(finalQuiz))
                .flatMap(Function.identity())
                .toList();
    }

    /** @return the quiz with this id, or empty when it belongs to some other course */
    public Optional<Quiz> quizById(Long quizId) {
        if (quizId == null) {
            return Optional.empty();
        }
        return allQuizzes().stream()
                .filter(quiz -> quizId.equals(quiz.getId()))
                .findFirst();
    }
}
