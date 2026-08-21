package com.manara.backend.course.service;

import com.manara.backend.lesson.repository.CompletedLessonRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.repository.QuizAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what a learner has done in a course, and hands it to the rules that decide what it earns
 * them.
 *
 * <p>Two queries, whatever the size of the course: every completed lesson, and every attempt made
 * anywhere in it. The alternative — asking each module and each exam about itself — is an N+1 that
 * grows with the curriculum, on a value that every learner-facing response needs.
 *
 * <p>The rules live in {@link CourseProgressionCalculator}; this class only supplies their inputs.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseProgressionService {

    private final CompletedLessonRepository completedLessonRepository;
    private final QuizAttemptRepository quizAttemptRepository;
    private final CourseProgressionCalculator courseProgressionCalculator;

    public CourseProgression progressionOf(CourseAggregate aggregate, Student student) {
        Long courseId = aggregate.course().getId();
        Set<Long> completedLessonIds = Set.copyOf(
                completedLessonRepository.findCompletedLessonIdsByStudentIdAndCourseId(student.getId(), courseId));
        return recompute(aggregate, student, completedLessonIds);
    }

    /**
     * The progression a learner would have with this set of completed lessons.
     *
     * <p>Used right after a completion is written but before it is flushed: passing the updated set
     * in is both cheaper than reading it back and exactly what the next request would see.
     */
    public CourseProgression recompute(CourseAggregate aggregate, Student student, Set<Long> completedLessonIds) {
        return courseProgressionCalculator.compute(
                aggregate, completedLessonIds, attemptsByQuiz(student, aggregate.course().getId()));
    }

    private Map<Long, List<QuizAttempt>> attemptsByQuiz(Student student, Long courseId) {
        Map<Long, List<QuizAttempt>> byQuiz = new HashMap<>();
        for (QuizAttempt attempt : quizAttemptRepository.findCourseAttempts(student.getId(), courseId)) {
            byQuiz.computeIfAbsent(attempt.getQuiz().getId(), id -> new ArrayList<>()).add(attempt);
        }
        return byQuiz;
    }
}
