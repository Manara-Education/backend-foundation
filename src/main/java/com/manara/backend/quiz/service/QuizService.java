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
    public QuizSyncResult sync(QuizOwnerType ownerType, Long ownerId, QuizRequest request) {
        Quiz existing = quizRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId).orElse(null);

        if (request == null) {
            if (existing == null) {
                return QuizSyncResult.unchanged(null);
            }
            quizRepository.delete(existing);
            return QuizSyncResult.changed(null);
        }

        quizValidator.validate(request);

        if (existing == null) {
            return QuizSyncResult.changed(quizRepository.save(quizMapper.toQuiz(request, ownerType, ownerId)));
        }

        // Compared before assigned, throughout. Re-submitting a quiz unchanged has to come out as
        // "nothing happened", because the course above this decides from that answer whether to
        // tell every enrolled learner the course was updated.
        boolean changed = assign(existing.getTitle(), request.getTitle().trim(), existing::setTitle);
        changed |= assign(existing.getInstructions(), trimToNull(request.getInstructions()), existing::setInstructions);
        changed |= assign(existing.getPassingScore(), request.getPassingScore(), existing::setPassingScore);
        changed |= syncQuestions(existing, request.getQuestions());

        Quiz saved = quizRepository.save(existing);
        return changed ? QuizSyncResult.changed(saved) : QuizSyncResult.unchanged(saved);
    }

    /**
     * Assigns only when the value actually differs.
     *
     * @return whether anything was written
     */
    private <T> boolean assign(T current, T incoming, java.util.function.Consumer<T> setter) {
        if (java.util.Objects.equals(current, incoming)) {
            return false;
        }
        setter.accept(incoming);
        return true;
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

    /** @return whether any question was created, removed, reordered or edited */
    private boolean syncQuestions(Quiz quiz, List<QuizQuestionRequest> requests) {
        Map<String, QuizQuestion> existingById = new HashMap<>();
        for (QuizQuestion question : quiz.getQuestions()) {
            existingById.put(String.valueOf(question.getId()), question);
        }

        Set<QuizQuestion> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        List<QuizQuestion> created = new ArrayList<>();
        boolean changed = false;

        for (int order = 0; order < requests.size(); order++) {
            QuizQuestionRequest request = requests.get(order);
            QuizQuestion question = matchExisting(existingById, request.getId());

            if (question == null) {
                created.add(quizMapper.toQuestion(request, quiz, order));
                changed = true;
                continue;
            }

            changed |= assign(question.getText(), request.getText().trim(), question::setText);
            changed |= assign(question.getExplanation(), trimToNull(request.getExplanation()),
                    question::setExplanation);
            changed |= assign(question.getHintByAiEnabled(), Boolean.TRUE.equals(request.getHintByAiEnabled()),
                    question::setHintByAiEnabled);
            changed |= assign(question.getOrderIndex(), order, question::setOrderIndex);
            changed |= syncOptions(question, request);
            retained.add(question);
        }

        // Orphan removal turns this into deletes for the questions the request dropped.
        changed |= quiz.getQuestions().removeIf(question -> !retained.contains(question));
        created.forEach(quiz::addQuestion);
        return changed;
    }

    /**
     * @return whether any option was created, removed, reordered, re-worded or re-keyed
     *
     * <p>The answer key is granted in a second pass, after the option that held it has given it up
     * and that release has been flushed. {@code uk_quiz_options_single_correct} is a partial unique
     * index over {@code question_id WHERE is_correct}, so it is violated the moment two rows of one
     * question claim the key at once — and moving the key from option A to option B produces
     * exactly that if the grant reaches the database before the release. Which of the two statements
     * Hibernate emitted first was a matter of action-queue ordering, so an instructor correcting the
     * answer to an existing exam question was met with a constraint violation.
     */
    private boolean syncOptions(QuizQuestion question, QuizQuestionRequest request) {
        Map<String, QuizOption> existingById = new HashMap<>();
        for (QuizOption option : question.getOptions()) {
            existingById.put(String.valueOf(option.getId()), option);
        }

        Set<QuizOption> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        List<QuizOption> created = new ArrayList<>();
        String correctOptionId = request.getCorrectOptionId().trim();
        boolean changed = false;

        // The option that is to hold the key once this is done, whether it already exists or is
        // being created here. New options are built without it and granted it below, so that an
        // INSERT can never carry the key while the previous holder's UPDATE is still pending.
        QuizOption incomingKeyHolder = null;
        QuizOption currentKeyHolder = question.getOptions().stream()
                .filter(option -> Boolean.TRUE.equals(option.getCorrect()))
                .findFirst()
                .orElse(null);

        List<QuizOptionRequest> optionRequests = request.getOptions();
        for (int order = 0; order < optionRequests.size(); order++) {
            QuizOptionRequest optionRequest = optionRequests.get(order);
            boolean correct = correctOptionId.equals(optionRequest.getId().trim());
            QuizOption option = matchExisting(existingById, optionRequest.getId());

            if (option == null) {
                option = quizMapper.toOption(optionRequest, question, order, false);
                created.add(option);
                changed = true;
            } else {
                changed |= assign(option.getText(), optionRequest.getText().trim(), option::setText);
                changed |= assign(option.getOrderIndex(), order, option::setOrderIndex);
                retained.add(option);
            }

            if (correct) {
                incomingKeyHolder = option;
            }
        }

        boolean keyMoves = currentKeyHolder != null && currentKeyHolder != incomingKeyHolder;
        if (keyMoves) {
            currentKeyHolder.setCorrect(false);
            changed = true;
        }

        changed |= question.getOptions().removeIf(option -> !retained.contains(option));
        created.forEach(question::addOption);

        // Nothing holds the key at this point, so the index is trivially satisfied while the
        // release — and any option the request dropped — reaches the database.
        if (keyMoves) {
            quizRepository.flush();
        }

        if (incomingKeyHolder != null && !Boolean.TRUE.equals(incomingKeyHolder.getCorrect())) {
            incomingKeyHolder.setCorrect(true);
            changed = true;
        }
        return changed;
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
