package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.ModuleRequest;
import com.manara.backend.course.dto.SubscriptionPlanRequest;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.lesson.dto.LessonRequest;
import com.manara.backend.quiz.service.QuizValidator;
import com.manara.backend.video.service.VideoProviderResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/**
 * Every invariant a course payload has to satisfy, checked before anything is written.
 *
 * <p>Running the whole validation up front matters: course synchronization is destructive — it
 * deletes the modules, lessons and quizzes the payload no longer mentions — so a payload that is
 * going to be rejected must be rejected before any of that happens, not halfway through.
 *
 * <p>Quiz rules are delegated to {@link QuizValidator} at all four places a quiz can appear, so a
 * module exam is held to exactly the same standard as a lesson quiz.
 */
@Component
@RequiredArgsConstructor
public class CourseValidator {

    /**
     * How the resolver's refusals read once they are about lesson number N of a course payload.
     *
     * @see #lessonScopedCode(String)
     */
    private static final Map<String, String> LESSON_VIDEO_ERROR_CODES = Map.of(
            "error.video.urlRequired", "error.course.lessonVideoUrlRequired",
            "error.video.urlMalformed", "error.course.lessonVideoUrlMalformed",
            "error.video.providerUnsupported", "error.course.lessonVideoProviderUnsupported",
            "error.video.videoIdInvalid", "error.course.lessonVideoIdInvalid",
            "error.video.providerMismatch", "error.course.lessonVideoProviderMismatch");

    private final QuizValidator quizValidator;
    private final VideoProviderResolver videoProviderResolver;

    /**
     * Validates the payload and resolves the course-level settings it implies.
     *
     * @param existing                    the course being updated, or {@code null} when creating
     * @param persistedActiveLessonCount  lessons the course already has in its active structure,
     *                                    consulted only when the payload carries no content of its
     *                                    own — so a metadata-only publish is still checked, without
     *                                    paying for the query when the payload answers the question
     */
    public ResolvedCourseSettings resolveAndValidate(CourseRequest request, Course existing,
                                                     IntSupplier persistedActiveLessonCount) {
        CourseStructure structure = resolveStructure(request, existing);
        CourseStatus status = resolveStatus(request, existing);
        CourseAccessType accessType = resolveAccessType(request, existing);

        validateStructure(request, structure);
        validateStructureChange(request, existing, structure);
        validateContent(request, structure);
        validatePublishable(request, structure, status, persistedActiveLessonCount);

        BigDecimal purchasePrice = validateAccess(request, existing, accessType);

        return new ResolvedCourseSettings(structure, status, accessType, purchasePrice);
    }

    private CourseStructure resolveStructure(CourseRequest request, Course existing) {
        if (request.getStructure() != null) {
            return request.getStructure();
        }
        return existing != null ? existing.getStructure() : CourseStructure.FLAT;
    }

    private CourseStatus resolveStatus(CourseRequest request, Course existing) {
        if (request.getStatus() != null) {
            return request.getStatus();
        }
        return existing != null ? existing.getStatus() : CourseStatus.DRAFT;
    }

    /**
     * Clients written against the previous contract sent a price and no access type. Inferring
     * {@code PURCHASE} from a positive price keeps them working; an explicit access type always
     * wins, and an update that mentions neither keeps what the course already had.
     */
    private CourseAccessType resolveAccessType(CourseRequest request, Course existing) {
        if (request.getAccessType() != null) {
            return request.getAccessType();
        }
        BigDecimal price = request.resolvePurchasePrice();
        if (price != null) {
            return price.compareTo(BigDecimal.ZERO) > 0 ? CourseAccessType.PURCHASE : CourseAccessType.FREE;
        }
        return existing != null ? existing.getAccessType() : CourseAccessType.FREE;
    }

    /**
     * The structure is authoritative, so a payload may not carry content for the other side of the
     * tree. Rejecting it — rather than quietly dropping it — keeps the client from believing it
     * saved something the API discarded.
     */
    private void validateStructure(CourseRequest request, CourseStructure structure) {
        if (structure == CourseStructure.FLAT && isNotEmpty(request.getModules())) {
            throw new BusinessException("error.course.flatWithModules");
        }
        if (structure == CourseStructure.MODULES && isNotEmpty(request.getLessons())) {
            throw new BusinessException("error.course.modulesWithLessons");
        }
    }

    /**
     * Switching structure rearranges the whole content tree, so the payload has to say what the new
     * tree is. Guessing — keeping the old lessons, or dropping them — would either strand content
     * outside the active structure or destroy it silently.
     */
    private void validateStructureChange(CourseRequest request, Course existing, CourseStructure structure) {
        if (existing == null || existing.getStructure() == structure) {
            return;
        }
        if (!request.carriesContentFor(structure)) {
            throw new BusinessException("error.course.structureChangeRequiresContent");
        }
    }

    private void validateContent(CourseRequest request, CourseStructure structure) {
        quizValidator.validateIfPresent(request.getFinalQuiz());

        if (structure == CourseStructure.FLAT) {
            validateLessons(request.getLessons());
            return;
        }

        List<ModuleRequest> modules = request.getModules();
        if (modules == null) {
            return;
        }
        for (int i = 0; i < modules.size(); i++) {
            ModuleRequest module = modules.get(i);
            int position = i + 1;
            if (module == null || isBlank(module.getTitle())) {
                throw new BusinessException("error.course.moduleTitleRequired", position);
            }
            quizValidator.validateIfPresent(module.getQuiz());
            validateLessons(module.getLessons());
        }
    }

    private void validateLessons(List<LessonRequest> lessons) {
        if (lessons == null) {
            return;
        }
        for (int i = 0; i < lessons.size(); i++) {
            LessonRequest lesson = lessons.get(i);
            int position = i + 1;
            if (lesson == null || isBlank(lesson.getTitle())) {
                throw new BusinessException("error.course.lessonTitleRequired", position);
            }
            if (isBlank(lesson.getVideoUrl())) {
                throw new BusinessException("error.course.lessonVideoUrlRequired", position);
            }
            // Checked here, with the rest of the payload, precisely because synchronization is
            // destructive: a course whose fourth lesson carries an unplayable link must be turned
            // away before the first three have had their modules and quizzes rewritten.
            validateVideo(lesson, position);
            quizValidator.validateIfPresent(lesson.getQuiz());
        }
    }

    /**
     * Rejects a video Manara cannot play, naming the lesson that carries it.
     *
     * <p>The reason travels with the position, because "lesson 4 is on a platform we do not
     * support" and "lesson 4's link has no video in it" send an instructor to different fixes. The
     * message says which; it never says which adapter, host or pattern decided so.
     *
     * <p>This is a real tightening. The prototype accepted any non-blank string as a video URL,
     * which is how a typo became a lesson with a permanently empty player. Existing rows are not
     * affected: validation runs on the write path only, and the read path is deliberately lenient.
     */
    private void validateVideo(LessonRequest lesson, int position) {
        try {
            videoProviderResolver.resolve(lesson.getVideoUrl(), lesson.getVideoProvider());
        } catch (BusinessException e) {
            throw new BusinessException(lessonScopedCode(e.getMessageCode()), position);
        }
    }

    /**
     * The same reason, told about a numbered lesson.
     *
     * <p>The resolver states its complaint about a URL; inside a course payload the instructor
     * needs to know which of thirty lessons it was about. Translating the code rather than
     * embedding the resolver's rendered text keeps both halves of the sentence in the reader's
     * language and keeps the message catalogue the single place either is worded.
     *
     * <p>An unmapped code — a reason a future adapter invents — degrades to the generic invalid
     * message rather than surfacing a raw key.
     */
    private String lessonScopedCode(String videoErrorCode) {
        return LESSON_VIDEO_ERROR_CODES.getOrDefault(videoErrorCode, "error.course.lessonVideoUrlInvalid");
    }

    /**
     * The completeness rules a course has to satisfy to go live, checked against what is stored.
     *
     * <p>Entry point for the explicit publish operation, which has no payload to check. It is the
     * same single rule the payload path applies, stated once — a published course must actually
     * teach something.
     *
     * <p>Deliberately <em>not</em> reused as a gate on later edits. Publication validation says what
     * a course needs to become visible; treating it as a standing precondition for every subsequent
     * save is how "published" turns into "read-only", which is the coupling this work exists to
     * remove. What a published course may not do is edit its way into an invalid public state, and
     * that is enforced where it belongs: {@code validatePublishable(request, ...)} runs on every
     * update that leaves the course published, so deleting the last lesson of a live course is
     * refused while every other edit goes through.
     *
     * @param activeLessonCount lessons the course currently has in its active structure
     */
    public void validatePublishable(int activeLessonCount) {
        if (activeLessonCount == 0) {
            throw new BusinessException("error.course.publishRequiresLesson");
        }
    }

    /**
     * A published course must actually teach something. An empty module does not count — only real
     * lessons do, wherever they sit.
     */
    private void validatePublishable(CourseRequest request, CourseStructure structure, CourseStatus status,
                                     IntSupplier persistedActiveLessonCount) {
        if (status != CourseStatus.PUBLISHED) {
            return;
        }
        int lessonCount = request.carriesContentFor(structure)
                ? countLessons(request, structure)
                : persistedActiveLessonCount.getAsInt();

        if (lessonCount == 0) {
            throw new BusinessException("error.course.publishRequiresLesson");
        }
    }

    private int countLessons(CourseRequest request, CourseStructure structure) {
        if (structure == CourseStructure.FLAT) {
            return request.getLessons() == null ? 0 : request.getLessons().size();
        }
        if (request.getModules() == null) {
            return 0;
        }
        return request.getModules().stream()
                .map(ModuleRequest::getLessons)
                .filter(java.util.Objects::nonNull)
                .mapToInt(List::size)
                .sum();
    }

    /**
     * @return the purchase price to store — {@code null} for anything but a one-off purchase, so a
     * course can never keep a stale price after being switched to free or subscription access
     */
    private BigDecimal validateAccess(CourseRequest request, Course existing, CourseAccessType accessType) {
        BigDecimal purchasePrice = request.resolvePurchasePrice();

        return switch (accessType) {
            case PURCHASE -> {
                // Same "absent means untouched" rule the plans and the content tree follow. A
                // course that is already sold outright and says nothing about its price keeps the
                // price it has — without this, a metadata-only save of any paid course was refused
                // outright with "a purchase course must have a price", which made every published
                // paid course impossible to rename.
                BigDecimal effective = purchasePrice != null ? purchasePrice : existingPurchasePrice(existing);

                if (effective == null || effective.compareTo(BigDecimal.ZERO) <= 0) {
                    throw new BusinessException("error.course.purchasePriceRequired");
                }
                yield effective;
            }
            case SUBSCRIPTION -> {
                // Same "absent means untouched" rule the content tree follows: a course that is
                // already subscription-based and says nothing about plans keeps the ones it has.
                boolean keepsExistingPlans = request.getSubscriptionPlans() == null
                        && existing != null
                        && existing.getAccessType() == CourseAccessType.SUBSCRIPTION;
                if (!keepsExistingPlans) {
                    validateSubscriptionPlans(request.getSubscriptionPlans());
                }
                yield null;
            }
            case FREE -> null;
        };
    }

    /** The price a course already carries, but only while it is genuinely a purchase course. */
    private BigDecimal existingPurchasePrice(Course existing) {
        return existing != null && existing.getAccessType() == CourseAccessType.PURCHASE
                ? existing.getPurchasePrice()
                : null;
    }

    private void validateSubscriptionPlans(List<SubscriptionPlanRequest> plans) {
        if (plans == null || plans.isEmpty()) {
            throw new BusinessException("error.course.subscriptionPlansRequired");
        }
        for (int i = 0; i < plans.size(); i++) {
            SubscriptionPlanRequest plan = plans.get(i);
            int position = i + 1;
            if (plan == null || isBlank(plan.getName())) {
                throw new BusinessException("error.course.planNameRequired", position);
            }
            if (plan.getUnit() == null) {
                throw new BusinessException("error.course.planUnitRequired", position);
            }
            if (plan.getDuration() == null || plan.getDuration() <= 0) {
                throw new BusinessException("error.course.planDurationPositive", position);
            }
            if (plan.getPrice() == null || plan.getPrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BusinessException("error.course.planPricePositive", position);
            }
        }
    }

    private boolean isNotEmpty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
