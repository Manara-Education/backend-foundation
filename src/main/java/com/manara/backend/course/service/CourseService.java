package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ConflictException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.file.FileUploadService;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.LessonOrderRequest;
import com.manara.backend.course.dto.ModuleOrderRequest;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.model.TrackedContent;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.mapper.CourseAggregateMapper;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.mapper.EntitlementMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.service.view.CourseDetailsViewRegistry;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.profile.model.Instructor;
import com.manara.backend.profile.repository.InstructorRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.stream.Collectors;

/**
 * Course authoring and browsing.
 *
 * <p>Every aggregate mutation runs in one transaction covering the course, its modules, lessons,
 * quizzes, questions, options and plans. Validation is complete before synchronization starts, so a
 * rejected payload never leaves a half-rearranged course behind, and a failure anywhere in the
 * nested content rolls the whole edit back.
 *
 * <h2>Publication is not editability</h2>
 * A published course is fully editable by its instructor. Publication decides who can <em>see</em>
 * the course, not whether its author may change it, and the two are kept apart deliberately:
 * {@link #updateCourse} never changes the status by itself, and {@link #publish} / {@link #unpublish}
 * never touch content. A published course that is edited stays published.
 *
 * <h2>Publication is not the content version</h2>
 * What learners are told about is a third thing again, tracked by two timestamps on the course:
 * {@code lastPublishedAt} (the baseline) and {@code contentUpdatedAt} (the last real, instructor-made,
 * learner-visible change). Everything that mutates a course through this service records whether it
 * actually changed anything, and {@link CourseContentJournal} writes the timestamps once, inside the
 * same transaction as the change — so a rolled-back edit cannot leave a course claiming to be
 * updated, and a committed one cannot fail to say so.
 *
 * <p>{@code contentUpdatedAt} answers two different questions depending on what it is compared to.
 * Against {@code lastPublishedAt} it answers the instructor's — "have I edited since I published?".
 * Against an enrollment's {@code enrolledAt} it answers each learner's, separately, which is what
 * {@link CourseUpdateResolver} builds for the course-details screen.
 *
 * <h2>Pricing is not content</h2>
 * Repricing a course, changing its access type or editing its subscription plans never moves the
 * content version and never reaches an existing learner. That is enforced structurally rather than
 * remembered: those fields are applied through {@link #applyPricing}, which has no change recorder
 * to record into. See its Javadoc for what an existing enrollment is and is not affected by.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CourseService {

    private final CourseRepository courseRepository;
    private final LessonRepository lessonRepository;
    private final InstructorRepository instructorRepository;
    private final CourseMapper courseMapper;
    private final CourseAggregateMapper courseAggregateMapper;
    private final CourseAggregateLoader courseAggregateLoader;
    private final CourseValidator courseValidator;
    private final CourseContentSynchronizer courseContentSynchronizer;
    private final CourseContentJournal courseContentJournal;
    private final CourseUpdateResolver courseUpdateResolver;
    private final CourseDetailsViewRegistry courseDetailsViewRegistry;
    private final EntitlementMapper entitlementMapper;
    private final EntitlementPolicy entitlementPolicy;
    private final FileUploadService fileUploadService;
    private final CourseModuleRepository courseModuleRepository;
    private final CourseVisibility courseVisibility;
    private final Clock clock;

    /** Catalogue for instructors and admins — drafts included. */
    public List<CourseResponse> getAllCourses() {
        return courseRepository.findAllWithInstructor().stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    /** Catalogue for learners. Drafts are an instructor's work in progress and stay hidden. */
    public List<CourseResponse> getPublishedCourses() {
        return courseRepository.findAllByStatusWithInstructor(CourseStatus.PUBLISHED).stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    /**
     * The learner-facing course screen, in either of its two modes.
     *
     * <p>The response carries the viewer's own access alongside the content in both modes, because
     * both need it: discovery renders enrol, buy or subscribe from it — and "continue learning" when
     * the visitor turns out to already hold the course — while the enrolled view renders the
     * subscription's remaining days, or its renewal offer once it has run out.
     */
    public CourseDetailsResponse getCourseDetails(User user, Long courseId, CourseViewMode mode) {
        // Not "is it published" but "may this viewer see it": a learner who already holds the
        // course keeps it when the instructor withdraws it from the catalogue, and everybody else
        // is told it does not exist. See CourseVisibility.
        var course = courseVisibility.requireVisible(user, courseId);
        var aggregate = courseAggregateLoader.load(course);
        var progression = courseDetailsViewRegistry.get(mode).resolveProgression(user, aggregate);
        var access = entitlementMapper.toCourseAccessResponse(entitlementPolicy.accessOf(user, courseId));
        // Resolved from the authenticated user's own enrollment, in both view modes. Somebody who
        // holds the course and reached it from the catalogue is still its learner, and a visitor
        // who does not gets the window that reports nothing.
        var updates = courseUpdateResolver.resolve(user, aggregate);
        return courseAggregateMapper.toCourseDetailsResponse(aggregate, progression, access, updates);
    }

    public List<CourseResponse> getMyCourses(User user) {
        var instructor = requireInstructor(user);
        return courseRepository.findByInstructorIdWithInstructor(instructor.getId()).stream()
                .map(courseMapper::toCourseResponse)
                .toList();
    }

    /** The complete editor model: content tree, exams with their answer keys, pricing and status. */
    public InstructorCourseResponse getCourseForEditing(User user, Long courseId) {
        var course = requireOwnedCourse(user, courseId);
        return courseAggregateMapper.toInstructorCourseResponse(courseAggregateLoader.load(course));
    }

    @Transactional
    public InstructorCourseResponse createCourse(User user, CourseRequest request) {
        var instructor = requireInstructor(user);
        var settings = courseValidator.resolveAndValidate(request, null, () -> 0);

        var course = courseRepository.save(courseMapper.toCourse(request, instructor, settings));

        // A brand-new course is entirely new content, so the recorder's answer is a foregone
        // conclusion; it is threaded through anyway so there is one synchronization path, not two.
        var changes = new CourseContentChanges();
        courseContentSynchronizer.sync(course, request, settings, changes);

        LocalDateTime now = LocalDateTime.now(clock);
        courseContentJournal.commit(course, changes, now);
        // Creating a course straight into PUBLISHED is the wizard's "publish" action, and it is the
        // course's first publication — so it establishes the baseline rather than announcing itself
        // as an update to learners who have never seen it.
        if (settings.status() == CourseStatus.PUBLISHED) {
            course.markPublished(now);
        }

        return saveAndRespond(course);
    }

    /**
     * Applies an edit to a course, whatever its publication state.
     *
     * <p>Three rules hold here regardless of whether the course is a draft or live:
     *
     * <ul>
     *   <li><strong>Publication is untouched by default.</strong> A payload that says nothing about
     *       {@code status} leaves the course exactly as published or unpublished as it was. The
     *       field is still honoured when a client sends it, for the wizard and for clients written
     *       against the previous contract, but no ordinary content save carries it and nothing here
     *       ever infers it.
     *   <li><strong>Omitted is not empty.</strong> {@code subtitle} and {@code image} are only
     *       written when the payload actually mentioned them. Before that distinction existed, a
     *       metadata-only save blanked a published course's cover image.
     *   <li><strong>Duration is derived, never accepted.</strong> It is the sum of the lessons'
     *       durations and belongs to the server.
     * </ul>
     */
    @Transactional
    public InstructorCourseResponse updateCourse(User user, Long courseId, CourseRequest request) {
        // Locked before anything is read off it, and the revision checked before anything is
        // validated. A stale payload must not be able to reach the content synchronizer at all.
        var course = requireOwnedCourseForUpdate(user, courseId);
        requireCurrentRevision(course, request.getExpectedRevision());

        var settings = courseValidator.resolveAndValidate(request, course, () -> activeLessonCount(course));

        String previousImage = course.getImage();
        var changes = new CourseContentChanges();
        // One instant for the whole request. Reading the clock twice would put a publish a few
        // microseconds after the edit it carries, and "content newer than baseline" would then be
        // true for a course that had just been published with those very edits in it.
        LocalDateTime now = LocalDateTime.now(clock);

        var onCourse = changes.of(course);
        onCourse.metadata(course.getTitle(), request.getTitle().trim(), course::setTitle);
        onCourse.content(course.getDescription(), request.getDescription(), course::setDescription);
        if (request.carriesSubtitle()) {
            onCourse.metadata(course.getSubtitle(), request.subtitleValue(), course::setSubtitle);
        }
        if (request.carriesImage()) {
            onCourse.metadata(course.getImage(), request.imageValue(), course::setImage);
        }
        // A structure switch re-parents or discards content, so it is curriculum, not commerce.
        onCourse.content(course.getStructure(), settings.structure(), course::setStructure);

        // Recorded here rather than inside applyPricing, which still has no recorder to reach. The
        // only thing this records is that the aggregate moved on, which is what a stale tab has to
        // be told about — a price it never saw is a price its save would put back.
        if (repricing(course, settings)) {
            changes.recordUnannouncedChange();
        }
        applyPricing(course, settings);

        courseContentSynchronizer.sync(course, request, settings, changes);

        // Recorded before the journal and applied after it. The revision has to move for a
        // publication change too — it is part of the aggregate an editor holds — but the transition
        // itself must still run last, so a publish carrying edits sets its baseline on top of them
        // rather than a moment before them.
        if (course.getStatus() != settings.status()) {
            changes.recordUnannouncedChange();
        }

        courseContentJournal.commit(course, changes, now);

        // Last, and after the content it may be publishing. The status the validator resolved is
        // the course's own unless the payload named a different one, so an ordinary save is a no-op
        // here. A genuine transition goes through the lifecycle methods, which is what lets a
        // publish carrying edits establish its baseline on top of them rather than a moment before
        // them — publishing is not an edit, and must not announce itself as one.
        applyStatus(course, settings.status(), now);

        var response = saveAndRespond(course);

        // Deleting the replaced upload last: the file is gone for good, so it only happens once the
        // rest of the edit has been accepted. Only a payload that actually named a new image can
        // retire the old one — an update that never mentioned the cover leaves the file alone.
        if (previousImage != null
                && request.carriesImage()
                && request.imageValue() != null
                && !previousImage.equals(request.imageValue())) {
            fileUploadService.deleteFile(previousImage);
        }
        return response;
    }

    /**
     * Publishes a course. The one operation that makes a new version baseline.
     *
     * <p>Idempotent: re-publishing an already-published course is how an instructor says "what is
     * there now is the version I stand behind", which clears the Updated badge. That is a
     * deliberate product decision rather than an accident of the implementation, and it is the only
     * thing that clears it.
     */
    @Transactional
    public InstructorCourseResponse publish(User user, Long courseId) {
        var course = requireOwnedCourseForUpdate(user, courseId);

        // Publishing is where the completeness rules bite. An empty draft is a perfectly legal
        // draft; an empty published course is a broken catalogue entry.
        courseValidator.validatePublishable(activeLessonCount(course));

        course.markPublished(LocalDateTime.now(clock));
        // Publication is part of the aggregate an editor is holding, so a publish moves the
        // revision like any other accepted change and the editor adopts it from this response.
        course.nextRevision();
        log.info("Course published: courseId={} instructorUserId={}", courseId, user.getId());

        return saveAndRespond(course);
    }

    /**
     * Withdraws a course from the catalogue. Content, learners and their history are untouched.
     *
     * <p>Literally untouched, and now enforced rather than merely stated: a learner who already
     * holds the course keeps their library entry, their curriculum, their progress and their
     * attempt history while it is off the catalogue, and gets them all back unchanged when it
     * returns. What unpublishing removes is discovery and acquisition, not access. See
     * {@link CourseVisibility}.
     */
    @Transactional
    public InstructorCourseResponse unpublish(User user, Long courseId) {
        var course = requireOwnedCourseForUpdate(user, courseId);
        course.markUnpublished();
        course.nextRevision();
        log.info("Course unpublished: courseId={} instructorUserId={}", courseId, user.getId());
        return saveAndRespond(course);
    }

    /**
     * Rewrites the order of a course's modules from a list of ids.
     *
     * <p>A focused command rather than an aggregate save, and that is the point of it. Reordering
     * through the full-replacement {@code PUT} means shipping the whole course back — so a tab that
     * loaded the course before someone else renamed it would undo the rename just by dragging a
     * module. This touches {@code order_index} and nothing else.
     *
     * <p>Positions are derived from the array, never taken from the client: the first id becomes 0,
     * the second 1, and so on. A submitted position cannot therefore be duplicated, negative or
     * leave a gap, and the stored order comes out contiguous by construction.
     *
     * <p>The list has to name every module of the course exactly once. That is what makes a stale
     * reorder — one sent by a client whose module list has since changed — a rejection rather than a
     * half-applied order, and it is why a reorder that arrives after a module was deleted elsewhere
     * fails loudly instead of quietly dropping it.
     */
    @Transactional
    public InstructorCourseResponse reorderModules(User user, Long courseId, ModuleOrderRequest request) {
        var course = requireOwnedCourseForUpdate(user, courseId);

        // Locked for the rest of the transaction: two reorders of one course arriving together
        // would otherwise interleave into an order neither instructor asked for.
        return applyOrder(course, user, "reorderModules",
                request.getModuleIds(), courseModuleRepository.findByCourseIdForUpdate(courseId),
                CourseModule::getId, CourseModule::getOrderIndex, CourseModule::setOrderIndex,
                MODULE_ORDER_CODES);
    }

    /**
     * Rewrites the order of a {@code FLAT} course's root lessons.
     *
     * <p>The lesson-scope twin of {@link #reorderModules}, and it exists for exactly the same
     * reason: dragging a lesson should persist a lesson order, not re-submit the whole course. The
     * scope is the course's lessons that sit under no module, so running this against a
     * {@code MODULES} course finds an empty scope and is refused rather than silently doing
     * nothing.
     */
    @Transactional
    public InstructorCourseResponse reorderLessons(User user, Long courseId, LessonOrderRequest request) {
        var course = requireOwnedCourseForUpdate(user, courseId);

        return applyOrder(course, user, "reorderLessons",
                request.getLessonIds(), lessonRepository.findRootLessonsForUpdate(courseId),
                Lesson::getId, Lesson::getOrderIndex, Lesson::setOrderIndex,
                LESSON_ORDER_CODES);
    }

    /**
     * Rewrites the order of one module's lessons.
     *
     * <p>The third and last ordered scope a course has, and the one the editor was previously
     * unable to persist at all. Moving a lesson inside a module is a lesson-order operation: it
     * must never reach the module-order command, and it must never be expressed as an aggregate
     * save carrying a stale copy of the rest of the course.
     *
     * <p>The module is resolved against this course before anything is read, so a module id
     * belonging to somebody else's course is rejected as not found rather than reordered. The
     * lesson scope is then the lessons of that module and no others, which is what stops this
     * command from moving a lesson between modules — re-parenting is a structural edit and belongs
     * to the aggregate save.
     */
    @Transactional
    public InstructorCourseResponse reorderModuleLessons(User user, Long courseId, Long moduleId,
                                                         LessonOrderRequest request) {
        var course = requireOwnedCourseForUpdate(user, courseId);
        requireOwnedModule(courseId, moduleId);

        return applyOrder(course, user, "reorderModuleLessons",
                request.getLessonIds(), lessonRepository.findModuleLessonsForUpdate(courseId, moduleId),
                Lesson::getId, Lesson::getOrderIndex, Lesson::setOrderIndex,
                LESSON_ORDER_CODES);
    }

    /**
     * The one ordering command, wearing three names.
     *
     * <p>Modules, root lessons and a module's lessons are the same operation on three different
     * sibling collections, and writing it once is what stops them drifting apart — a validation
     * rule added for one scope cannot go missing from another, and none of them can grow its own
     * idea of what a no-op is.
     *
     * <p>The scope is passed in already locked and already narrowed to siblings the caller has
     * proved the instructor owns, so nothing here has to be told which course it is working on:
     * an id that is not in {@code siblings} is unreachable, whoever it belongs to.
     */
    private <T extends TrackedContent> InstructorCourseResponse applyOrder(
            Course course, User user, String operation,
            List<Long> requestedIds, List<T> siblings,
            Function<T, Long> idOf, ToIntFunction<T> positionOf,
            BiConsumer<T, Integer> setPosition, OrderCodes codes) {
        List<Long> ids = requireWellFormedOrder(requestedIds, codes);
        requireCompleteOrder(ids, siblings, idOf, codes);

        Map<Long, T> byId = siblings.stream().collect(Collectors.toMap(idOf, Function.identity()));

        var changes = new CourseContentChanges();
        for (int position = 0; position < ids.size(); position++) {
            T sibling = byId.get(ids.get(position));
            int current = positionOf.applyAsInt(sibling);
            int next = position;
            // Recorded against the sibling that actually moved, so a learner is pointed at the two
            // rows that swapped rather than at every row in the scope.
            changes.of(sibling).reordered(current, next, value -> setPosition.accept(sibling, value));
        }

        // A reorder that asked for the order the scope is already in writes nothing and, crucially,
        // does not tell every enrolled learner the course changed.
        boolean recorded = courseContentJournal.commit(course, changes, LocalDateTime.now(clock));
        if (recorded) {
            log.info("Course content reordered: courseId={} instructorUserId={} operation={} siblingCount={}",
                    course.getId(), user.getId(), operation, ids.size());
        }

        return saveAndRespond(course);
    }

    /** Rejects a malformed payload before it is ever compared against the course. */
    private List<Long> requireWellFormedOrder(List<Long> ids, OrderCodes codes) {
        if (ids == null) {
            throw new BusinessException(codes.required());
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(codes.nullId());
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException(codes.duplicate());
        }
        return ids;
    }

    /**
     * The whole scope, exactly once.
     *
     * <p>Set equality, not merely "every id exists": an id from another instructor's course is not
     * in this scope's siblings and is rejected here, and a list that silently omits one is rejected
     * too rather than leaving that sibling stranded at whatever position it happened to hold.
     *
     * <p>An empty scope is not a special case. A course with no modules, or a module with no
     * lessons, accepts only the empty list — so a reorder aimed at the wrong structure, or at a
     * scope whose contents were deleted in another tab, comes back as an incomplete order rather
     * than a silent success.
     */
    private <T> void requireCompleteOrder(List<Long> requestedIds, List<T> siblings,
                                          Function<T, Long> idOf, OrderCodes codes) {
        Set<Long> owned = siblings.stream().map(idOf).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> requested = new LinkedHashSet<>(requestedIds);

        List<Long> unknown = new ArrayList<>(requested);
        unknown.removeAll(owned);
        if (!unknown.isEmpty()) {
            throw new BusinessException(codes.notInScope(), unknown.get(0));
        }

        if (requested.size() != owned.size()) {
            throw new BusinessException(codes.incomplete(), owned.size(), requested.size());
        }
    }

    /**
     * Resolves a module against the course in the path.
     *
     * <p>Separate from the lesson lookup on purpose: a module id that belongs to another course
     * has to fail as a missing module, not as an empty lesson scope, or a caller probing for other
     * instructors' module ids could tell the two apart.
     */
    private CourseModule requireOwnedModule(Long courseId, Long moduleId) {
        return courseModuleRepository.findByIdAndCourseId(moduleId, courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.moduleNotInCourse",
                        String.valueOf(moduleId)));
    }

    /** The message keys one ordered scope answers with, so the shared command can stay scope-blind. */
    private record OrderCodes(String required, String nullId, String duplicate, String notInScope,
                              String incomplete) {
    }

    private static final OrderCodes MODULE_ORDER_CODES = new OrderCodes(
            "error.course.moduleOrderRequired", "error.course.moduleOrderNullId",
            "error.course.moduleOrderDuplicate", "error.course.moduleNotInCourse",
            "error.course.moduleOrderIncomplete");

    private static final OrderCodes LESSON_ORDER_CODES = new OrderCodes(
            "error.course.lessonOrderRequired", "error.course.lessonOrderNullId",
            "error.course.lessonOrderDuplicate", "error.course.lessonNotInScope",
            "error.course.lessonOrderIncomplete");

    /**
     * Applies what the course costs — deliberately without the change recorder in reach.
     *
     * <h4>Repricing is not editing</h4>
     * A learner who paid 500 for this course is unaffected by it becoming 700: their
     * {@code CourseEntitlement} is a standing grant that is never re-read against the current price,
     * their {@code Enrollment} is untouched, and what they were charged is recorded on their own
     * {@code CoursePurchase} or {@code CourseSubscription} row rather than derived from here. What
     * must also not happen is the third thing — telling them the course they are studying has
     * changed, when the only thing that changed is what it would cost somebody else today.
     *
     * <p>Before this separation existed, {@code changes.recordIf(!sameAmount(...))} sat inline with
     * the title and description and did exactly that. The fix is not to remember to leave price out;
     * it is that price is applied here, where the recorder is not a parameter and cannot be reached.
     * The same holds for {@code accessType} and for the subscription plans, which
     * {@code CourseContentSynchronizer} applies through its own pricing path.
     */
    private void applyPricing(Course course, ResolvedCourseSettings settings) {
        course.setAccessType(settings.accessType());
        course.setPurchasePrice(settings.purchasePrice());
    }

    /**
     * Applies a status the payload asked for, through the lifecycle methods.
     *
     * <p>Same status in, nothing happens — which is the case for every ordinary content save, since
     * the validator resolves an absent status to the course's own. A genuine transition still runs
     * the publication rules: publishing this way sets a baseline exactly as the dedicated endpoint
     * does, so the two can never disagree.
     */
    private void applyStatus(Course course, CourseStatus status, LocalDateTime at) {
        if (course.getStatus() == status) {
            return;
        }
        if (status == CourseStatus.PUBLISHED) {
            course.markPublished(at);
        } else {
            course.markUnpublished();
        }
    }

    /**
     * Flushes before reading the aggregate back so freshly created modules, lessons, quizzes,
     * questions and options are returned with their final persisted ids.
     */
    private InstructorCourseResponse saveAndRespond(Course course) {
        courseRepository.saveAndFlush(course);
        return courseAggregateMapper.toInstructorCourseResponse(courseAggregateLoader.load(course));
    }

    private Instructor requireInstructor(User user) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        return instructorRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.instructorNotFound", user.getId().toString()));
    }

    /**
     * The single ownership gate for course authoring. Every nested module, lesson and quiz is
     * reached through the course checked here — none of them is ever looked up by a client-supplied
     * id on its own.
     */
    private Course requireOwnedCourse(User user, Long courseId) {
        return requireOwnership(user, courseId, courseRepository.findById(courseId));
    }

    /**
     * The same gate, holding the course row for the rest of the transaction.
     *
     * <p>Every authoring write goes through this rather than {@link #requireOwnedCourse}, and it is
     * always the first lock taken. Two reasons, and both are about two requests arriving at once:
     * the revision check and its increment have to be one indivisible step, and every path that
     * moves the revision has to take its locks in the same order or two of them can wait on each
     * other. Ownership is still checked before anything is read off the course, and a course
     * belonging to somebody else is refused after the lock exactly as it was before — the lock is
     * on one row of one course and grants no information about it.
     */
    private Course requireOwnedCourseForUpdate(User user, Long courseId) {
        return requireOwnership(user, courseId, courseRepository.findByIdForUpdate(courseId));
    }

    private Course requireOwnership(User user, Long courseId, java.util.Optional<Course> found) {
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        var course = found
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }
        return course;
    }

    /**
     * Refuses an aggregate save that was built from a revision of the course that has since moved.
     *
     * <p>The aggregate {@code PUT} is full replacement, so accepting a stale payload does not merely
     * lose the newer edit — it actively restores every field the stale client was holding. Two tabs
     * were enough: one switched a course to {@code PURCHASE} at 199 and saved, the other renamed a
     * lesson from a copy loaded beforehand, and the course went back to free. Both were answered
     * {@code 200}.
     *
     * <p>Nothing is written when this refuses. It runs before validation and before the content
     * synchronizer, on a course row this transaction holds a lock on, so the losing request cannot
     * have touched the course, its content version or its learners' badge — a rejected save is not
     * a mutation.
     *
     * <p>The revision is required rather than optional. An update that cannot say what it was built
     * from cannot be checked, and treating that as "no precondition" would leave the guarantee opt-out
     * — the one shape of client this exists to stop is exactly the one that would not send it.
     */
    private void requireCurrentRevision(Course course, Long expected) {
        if (expected == null) {
            throw new BusinessException(ErrorCode.COURSE_REVISION_REQUIRED, "error.course.revisionRequired");
        }
        long current = revisionOf(course);
        if (expected != current) {
            log.info("Stale course save rejected: courseId={} expectedRevision={} currentRevision={}",
                    course.getId(), expected, current);
            throw new ConflictException(ErrorCode.COURSE_VERSION_CONFLICT, "error.course.versionConflict");
        }
    }

    /** Rows written before the column existed read as revision zero rather than as no revision. */
    private static long revisionOf(Course course) {
        return course.getRevision() == null ? 0L : course.getRevision();
    }

    /**
     * Whether this save changes what the course costs or how it is sold.
     *
     * <p>Read before {@link #applyPricing} writes, and only so the revision can move: a price
     * change is still not content, still does not stamp {@code contentUpdatedAt} and still never
     * reaches a learner's badge.
     */
    private boolean repricing(Course course, ResolvedCourseSettings settings) {
        if (course.getAccessType() != settings.accessType()) {
            return true;
        }
        var current = course.getPurchasePrice();
        var next = settings.purchasePrice();
        if (current == null || next == null) {
            return current != next;
        }
        // compareTo, not equals: 99.99 re-sent as 99.990 is the same price.
        return current.compareTo(next) != 0;
    }

    private int activeLessonCount(Course course) {
        return course.getStructure() == CourseStructure.MODULES
                ? lessonRepository.countByCourseIdAndModuleIsNotNull(course.getId())
                : lessonRepository.countByCourseIdAndModuleIsNull(course.getId());
    }
}
