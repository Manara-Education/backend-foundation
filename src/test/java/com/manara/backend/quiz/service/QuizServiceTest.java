package com.manara.backend.quiz.service;

import com.manara.backend.quiz.dto.QuizOptionRequest;
import com.manara.backend.quiz.dto.QuizQuestionRequest;
import com.manara.backend.quiz.dto.QuizRequest;
import com.manara.backend.quiz.mapper.QuizMapper;
import com.manara.backend.quiz.model.Quiz;
import com.manara.backend.quiz.model.QuizOption;
import com.manara.backend.quiz.model.QuizOwnerType;
import com.manara.backend.quiz.model.QuizQuestion;
import com.manara.backend.quiz.repository.QuizQuestionRepository;
import com.manara.backend.quiz.repository.QuizRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Covers the synchronization contract: editing a quiz updates it in place instead of replacing it,
 * so ids survive every edit. Stable ids are what future attempts, analytics and auditing hang off.
 */
@ExtendWith(MockitoExtension.class)
class QuizServiceTest {

    @Mock
    private QuizRepository quizRepository;

    @Mock
    private QuizQuestionRepository quizQuestionRepository;

    private QuizService quizService;

    @BeforeEach
    void setUp() {
        quizService = new QuizService(quizRepository, quizQuestionRepository, new QuizMapper(), new QuizValidator());
        lenientSave();
    }

    private void lenientSave() {
        org.mockito.Mockito.lenient()
                .when(quizRepository.save(any(Quiz.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsTheWholeGraphWithTheAnswerKeyResolvedFromClientReferences() {
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.LESSON, 7L)).willReturn(Optional.empty());

        Quiz quiz = quizService.sync(QuizOwnerType.LESSON, 7L, quiz(
                question(null, "option-2", option("option-1", "Answer 1"), option("option-2", "Answer 2"))));

        assertThat(quiz.getOwnerType()).isEqualTo(QuizOwnerType.LESSON);
        assertThat(quiz.getOwnerId()).isEqualTo(7L);
        assertThat(quiz.getQuestions()).hasSize(1);

        List<QuizOption> options = quiz.getQuestions().getFirst().getOptions();
        assertThat(options).extracting(QuizOption::getText).containsExactly("Answer 1", "Answer 2");
        assertThat(options).extracting(QuizOption::getCorrect).containsExactly(false, true);
    }

    @Test
    void assignsOrderFromPositionRatherThanInsertionOrder() {
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.COURSE, 3L)).willReturn(Optional.empty());

        Quiz quiz = quizService.sync(QuizOwnerType.COURSE, 3L, quiz(
                question(null, "a", option("a", "A"), option("b", "B")),
                question(null, "c", option("c", "C"), option("d", "D"))));

        assertThat(quiz.getQuestions()).extracting(QuizQuestion::getOrderIndex).containsExactly(0, 1);
        assertThat(quiz.getQuestions().getFirst().getOptions())
                .extracting(QuizOption::getOrderIndex).containsExactly(0, 1);
    }

    @Test
    void removesTheQuizWhenTheOwnerSendsNone() {
        Quiz existing = persistedQuiz();
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.MODULE, 5L)).willReturn(Optional.of(existing));

        Quiz result = quizService.sync(QuizOwnerType.MODULE, 5L, null);

        assertThat(result).isNull();
        verify(quizRepository).delete(existing);
    }

    @Test
    void doesNothingWhenAnOwnerWithoutAQuizStillHasNone() {
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.MODULE, 5L)).willReturn(Optional.empty());

        assertThat(quizService.sync(QuizOwnerType.MODULE, 5L, null)).isNull();
        verify(quizRepository, never()).delete(any());
    }

    @Test
    void editingAQuestionKeepsItsIdInsteadOfReplacingIt() {
        Quiz existing = persistedQuiz();
        Long questionId = existing.getQuestions().getFirst().getId();
        Long optionId = existing.getQuestions().getFirst().getOptions().getFirst().getId();
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.LESSON, 1L)).willReturn(Optional.of(existing));

        Quiz updated = quizService.sync(QuizOwnerType.LESSON, 1L, quiz(
                question(String.valueOf(questionId), String.valueOf(optionId),
                        option(String.valueOf(optionId), "Edited answer"),
                        option(String.valueOf(existing.getQuestions().getFirst().getOptions().get(1).getId()), "B"))));

        assertThat(updated.getQuestions()).hasSize(1);
        assertThat(updated.getQuestions().getFirst().getId()).isEqualTo(questionId);
        assertThat(updated.getQuestions().getFirst().getOptions().getFirst().getId()).isEqualTo(optionId);
        assertThat(updated.getQuestions().getFirst().getOptions().getFirst().getText()).isEqualTo("Edited answer");
    }

    @Test
    void addsNewQuestionsAndDropsTheOnesTheRequestNoLongerMentions() {
        Quiz existing = persistedQuiz();
        existing.addQuestion(question(existing, 20L, "Second question", 1));
        Long keptQuestionId = existing.getQuestions().getFirst().getId();
        Long keptOptionId = existing.getQuestions().getFirst().getOptions().getFirst().getId();
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.LESSON, 1L)).willReturn(Optional.of(existing));

        Quiz updated = quizService.sync(QuizOwnerType.LESSON, 1L, quiz(
                question(String.valueOf(keptQuestionId), String.valueOf(keptOptionId),
                        option(String.valueOf(keptOptionId), "A"),
                        option("new-option", "B")),
                question(null, "x", option("x", "X"), option("y", "Y"))));

        assertThat(updated.getQuestions()).hasSize(2);
        assertThat(updated.getQuestions()).extracting(QuizQuestion::getId).containsExactly(keptQuestionId, null);
        // The question that used to be second is gone rather than left behind as an orphan.
        assertThat(updated.getQuestions()).noneMatch(question -> Long.valueOf(20L).equals(question.getId()));
    }

    @Test
    void movingTheCorrectAnswerFlipsTheFlagOnBothOptions() {
        Quiz existing = persistedQuiz();
        QuizQuestion question = existing.getQuestions().getFirst();
        Long firstOptionId = question.getOptions().get(0).getId();
        Long secondOptionId = question.getOptions().get(1).getId();
        assertThat(question.getOptions().get(0).getCorrect()).isTrue();
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.LESSON, 1L)).willReturn(Optional.of(existing));

        Quiz updated = quizService.sync(QuizOwnerType.LESSON, 1L, quiz(
                question(String.valueOf(question.getId()), String.valueOf(secondOptionId),
                        option(String.valueOf(firstOptionId), "A"),
                        option(String.valueOf(secondOptionId), "B"))));

        assertThat(updated.getQuestions().getFirst().getOptions())
                .extracting(QuizOption::getCorrect).containsExactly(false, true);
    }

    @Test
    void reorderingQuestionsRewritesTheirStoredOrder() {
        Quiz existing = persistedQuiz();
        existing.addQuestion(question(existing, 20L, "Second question", 1));
        QuizQuestion first = existing.getQuestions().get(0);
        QuizQuestion second = existing.getQuestions().get(1);
        given(quizRepository.findByOwnerTypeAndOwnerId(QuizOwnerType.LESSON, 1L)).willReturn(Optional.of(existing));

        quizService.sync(QuizOwnerType.LESSON, 1L, quiz(
                questionFor(second),
                questionFor(first)));

        assertThat(second.getOrderIndex()).isZero();
        assertThat(first.getOrderIndex()).isEqualTo(1);
    }

    @Test
    void deletingAnOwnersQuizGoesThroughEntityRemovalSoQuestionsAndOptionsGoWithIt() {
        Quiz existing = persistedQuiz();
        given(quizRepository.findByOwnerTypeAndOwnerIdIn(QuizOwnerType.LESSON, List.of(1L, 2L)))
                .willReturn(List.of(existing));

        quizService.deleteByOwners(QuizOwnerType.LESSON, List.of(1L, 2L));

        // A bulk JPQL delete would bypass cascade and orphan removal and strand the children.
        verify(quizRepository).deleteAll(List.of(existing));
    }

    // --- fixtures -----------------------------------------------------------

    private static final AtomicLong IDS = new AtomicLong(100);

    private static Quiz persistedQuiz() {
        Quiz quiz = Quiz.builder()
                .id(1L)
                .ownerType(QuizOwnerType.LESSON)
                .ownerId(1L)
                .title("Existing quiz")
                .passingScore(60)
                .questions(new ArrayList<>())
                .build();
        quiz.addQuestion(question(quiz, 10L, "First question", 0));
        return quiz;
    }

    private static QuizQuestion question(Quiz quiz, Long id, String text, int orderIndex) {
        QuizQuestion question = QuizQuestion.builder()
                .id(id)
                .quiz(quiz)
                .text(text)
                .orderIndex(orderIndex)
                .hintByAiEnabled(false)
                .options(new ArrayList<>())
                .build();
        question.addOption(QuizOption.builder()
                .id(IDS.incrementAndGet()).text("A").correct(true).orderIndex(0).build());
        question.addOption(QuizOption.builder()
                .id(IDS.incrementAndGet()).text("B").correct(false).orderIndex(1).build());
        return question;
    }

    /** Echoes a persisted question back as a request, the way an editor round-trips one. */
    private static QuizQuestionRequest questionFor(QuizQuestion question) {
        List<QuizOptionRequest> options = question.getOptions().stream()
                .map(option -> option(String.valueOf(option.getId()), option.getText()))
                .toList();
        String correctOptionId = question.getOptions().stream()
                .filter(QuizOption::getCorrect)
                .map(option -> String.valueOf(option.getId()))
                .findFirst()
                .orElseThrow();

        return QuizQuestionRequest.builder()
                .id(String.valueOf(question.getId()))
                .text(question.getText())
                .correctOptionId(correctOptionId)
                .options(new ArrayList<>(options))
                .build();
    }

    private static QuizRequest quiz(QuizQuestionRequest... questions) {
        return QuizRequest.builder()
                .title("Quiz")
                .passingScore(70)
                .questions(new ArrayList<>(List.of(questions)))
                .build();
    }

    private static QuizQuestionRequest question(String id, String correctOptionId, QuizOptionRequest... options) {
        return QuizQuestionRequest.builder()
                .id(id)
                .text("Question text")
                .correctOptionId(correctOptionId)
                .options(new ArrayList<>(List.of(options)))
                .build();
    }

    private static QuizOptionRequest option(String id, String text) {
        return QuizOptionRequest.builder().id(id).text(text).build();
    }
}
