package com.manara.backend.quiz.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.service.CourseViewer;
import com.manara.backend.course.service.LearnerCourseAccess;
import com.manara.backend.quiz.dto.QuizAttemptResponse;
import com.manara.backend.quiz.dto.QuizSubmissionRequest;
import com.manara.backend.quiz.mapper.QuizAttemptMapper;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizAttempt;
import com.manara.backend.quiz.repository.QuizAttemptRepository;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Taking a quiz: the learner submits answers, the server decides the result.
 *
 * <p>The client's part of this is the submitted option ids and nothing else. The score, the pass
 * mark and the verdict are read from the stored quiz and written to the attempt here, so a
 * tampered payload changes which options were chosen and never what they were worth.
 *
 * <p>Four things must hold before a submission is graded, and they are checked in this order: the
 * caller is an enrolled learner of a published course, the quiz belongs to <em>that</em> course,
 * the curriculum has opened it, and every submitted question and option belongs to the quiz. The
 * first three are settled by the resolved {@link CourseViewer}; the last is the grader's.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizAttemptService {

    private final LearnerCourseAccess learnerCourseAccess;
    private final QuizAttemptRepository quizAttemptRepository;
    private final QuizAttemptMapper quizAttemptMapper;
    private final QuizGrader quizGrader;

    @Transactional
    public QuizAttemptResponse submit(User user, Long courseId, Long quizId, QuizSubmissionRequest request) {
        CourseViewer viewer = learnerCourseAccess.requireEnrolled(user, courseId);

        // Resolved against the quizzes this course actually owns. A quiz id from another course —
        // or from a course the learner has not paid for — resolves to nothing, so it is refused
        // before any of its content is read.
        Quiz quiz = viewer.aggregate().quizById(quizId)
                .orElseThrow(() -> new BusinessException("error.quiz.notInCourse", quizId));

        if (!viewer.progression().stateOf(quiz).available()) {
            throw new BusinessException("error.quiz.locked");
        }

        GradedQuiz graded = quizGrader.grade(quiz, request == null ? null : request.getAnswers());

        int attemptNumber = quizAttemptRepository.countByStudentIdAndQuizId(viewer.student().getId(), quizId) + 1;
        QuizAttempt attempt = quizAttemptRepository.save(quizAttemptMapper.toQuizAttempt(
                quiz, viewer.student(), viewer.course(), attemptNumber, graded));

        return quizAttemptMapper.toQuizAttemptResponse(attempt, graded);
    }
}
