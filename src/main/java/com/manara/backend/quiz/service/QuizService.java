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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The one quiz application service. A lesson quiz, a module exam and a course final exam are the
 * same operation with a different owner — there is deliberately no {@code LessonQuizService} or
 * {@code ModuleQuizService} duplicating any of this.
 *
 * <p>Authorization is <em>not</em> done here. Callers own the context needed to decide it (which
 * course the owner belongs to, who is editing) and must have verified it before calling. This
 * service never resolves an owner from client input on its own.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizService {

    private final QuizRepository quizRepository;
    private final QuizQuestionRepository quizQuestionRepository;
    private final QuizMapper quizMapper;
    private final QuizValidator quizValidator;

    public Optional<Quiz> findByOwner(QuizOwnerType ownerType, Long ownerId) {
        return Optional.ofNullable(findByOwners(ownerType, List.of(ownerId)).get(ownerId));
    }

    /**
     * Loads every quiz of the given owners, questions and options included, in a fixed number of
     * queries. The unique constraint on {@code (owner_type, owner_id)} is what makes the result a
     * plain map.
     */
    public Map<Long, Quiz> findByOwners(QuizOwnerType ownerType, Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }

        List<Quiz> quizzes = quizRepository.findByOwnerTypeAndOwnerIdInWithQuestions(ownerType, ownerIds);
        if (quizzes.isEmpty()) {
            return Map.of();
        }

        // Hydrates every question's options inside this persistence context, so the entities reached
        // through quiz.getQuestions() below are already complete.
        List<Long> quizIds = quizzes.stream().map(Quiz::getId).toList();
        quizQuestionRepository.findAllByQuizIdInWithOptions(quizIds);

        return quizzes.stream().collect(Collectors.toMap(Quiz::getOwnerId, Function.identity()));
    }

    /**
     * Brings the owner's quiz in line with the request: creates it, updates it in place, or removes
     * it when {@code request} is {@code null}.
     *
     * <p>Existing questions and options are matched by id and updated rather than replaced, so ids
     * stay stable for future attempts, analytics and auditing. Only children genuinely absent from
     * the request are deleted.
     */
    @Transactional
    public Quiz sync(QuizOwnerType ownerType, Long ownerId, QuizRequest request) {
        Quiz existing = quizRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId).orElse(null);

        if (request == null) {
            if (existing != null) {
                quizRepository.delete(existing);
            }
            return null;
        }

        quizValidator.validate(request);

        if (existing == null) {
            return quizRepository.save(quizMapper.toQuiz(request, ownerType, ownerId));
        }

        existing.setTitle(request.getTitle().trim());
        existing.setInstructions(trimToNull(request.getInstructions()));
        existing.setPassingScore(request.getPassingScore());
        syncQuestions(existing, request.getQuestions());
        return quizRepository.save(existing);
    }

    @Transactional
    public void deleteByOwner(QuizOwnerType ownerType, Long ownerId) {
        quizRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId).ifPresent(quizRepository::delete);
    }

    /**
     * Removes the quizzes of owners that are going away. Entity removal — not a bulk delete — so
     * cascade and orphan removal clear the questions and options too.
     */
    @Transactional
    public void deleteByOwners(QuizOwnerType ownerType, Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return;
        }
        quizRepository.deleteAll(quizRepository.findByOwnerTypeAndOwnerIdIn(ownerType, ownerIds));
    }

    private void syncQuestions(Quiz quiz, List<QuizQuestionRequest> requests) {
        Map<String, QuizQuestion> existingById = new HashMap<>();
        for (QuizQuestion question : quiz.getQuestions()) {
            existingById.put(String.valueOf(question.getId()), question);
        }

        Set<QuizQuestion> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        List<QuizQuestion> created = new ArrayList<>();

        for (int order = 0; order < requests.size(); order++) {
            QuizQuestionRequest request = requests.get(order);
            QuizQuestion question = matchExisting(existingById, request.getId());

            if (question == null) {
                created.add(quizMapper.toQuestion(request, quiz, order));
                continue;
            }

            question.setText(request.getText().trim());
            question.setExplanation(trimToNull(request.getExplanation()));
            question.setHintByAiEnabled(Boolean.TRUE.equals(request.getHintByAiEnabled()));
            question.setOrderIndex(order);
            syncOptions(question, request);
            retained.add(question);
        }

        // Orphan removal turns this into deletes for the questions the request dropped.
        quiz.getQuestions().removeIf(question -> !retained.contains(question));
        created.forEach(quiz::addQuestion);
    }

    private void syncOptions(QuizQuestion question, QuizQuestionRequest request) {
        Map<String, QuizOption> existingById = new HashMap<>();
        for (QuizOption option : question.getOptions()) {
            existingById.put(String.valueOf(option.getId()), option);
        }

        Set<QuizOption> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        List<QuizOption> created = new ArrayList<>();
        String correctOptionId = request.getCorrectOptionId().trim();

        List<QuizOptionRequest> optionRequests = request.getOptions();
        for (int order = 0; order < optionRequests.size(); order++) {
            QuizOptionRequest optionRequest = optionRequests.get(order);
            boolean correct = correctOptionId.equals(optionRequest.getId().trim());
            QuizOption option = matchExisting(existingById, optionRequest.getId());

            if (option == null) {
                created.add(quizMapper.toOption(optionRequest, question, order, correct));
                continue;
            }

            option.setText(optionRequest.getText().trim());
            option.setOrderIndex(order);
            option.setCorrect(correct);
            retained.add(option);
        }

        question.getOptions().removeIf(option -> !retained.contains(option));
        created.forEach(question::addOption);
    }

    /**
     * Resolves a request id against the children the parent already owns, and nothing else. A
     * client-generated reference for a new child simply finds no match; an id belonging to another
     * quiz can never be reached, because it was never in this map.
     */
    private <T> T matchExisting(Map<String, T> existingById, String requestedId) {
        if (requestedId == null || requestedId.isBlank()) {
            return null;
        }
        return existingById.remove(requestedId.trim());
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
