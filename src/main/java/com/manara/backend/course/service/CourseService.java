package com.manara.backend.course.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.common.file.FileUploadService;
import com.manara.backend.course.dto.CourseDetailsResponse;
import com.manara.backend.course.dto.CourseRequest;
import com.manara.backend.course.dto.CourseResponse;
import com.manara.backend.course.dto.CourseViewMode;
import com.manara.backend.course.dto.InstructorCourseResponse;
import com.manara.backend.course.dto.ModuleOrderRequest;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.repository.CourseModuleRepository;
import com.manara.backend.course.mapper.CourseAggregateMapper;
import com.manara.backend.course.mapper.CourseMapper;
import com.manara.backend.course.mapper.EntitlementMapper;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.service.view.CourseDetailsViewRegistry;
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
import java.util.function.Function;
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
 * actually changed anything, and the timestamp is written once, here, inside the same transaction as
 * the change — so a rolled-back edit cannot leave a course claiming to be updated, and a committed
 * one cannot fail to say so.
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
    private final CourseDetailsViewRegistry courseDetailsViewRegistry;
    private final EntitlementMapper entitlementMapper;
    private final EntitlementPolicy entitlementPolicy;
    private final FileUploadService fileUploadService;
    private final CourseModuleRepository courseModuleRepository;
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
        var course = findPublishedCourse(courseId);
        var aggregate = courseAggregateLoader.load(course);
        var progression = courseDetailsViewRegistry.get(mode).resolveProgression(user, aggregate);
        var access = entitlementMapper.toCourseAccessResponse(entitlementPolicy.accessOf(user, courseId));
        return courseAggregateMapper.toCourseDetailsResponse(aggregate, progression, access);
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
        course.markContentChanged(now);
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
        var course = requireOwnedCourse(user, courseId);
        var settings = courseValidator.resolveAndValidate(request, course, () -> activeLessonCount(course));

        String previousImage = course.getImage();
        var changes = new CourseContentChanges();
        // One instant for the whole request. Reading the clock twice would put a publish a few
        // microseconds after the edit it carries, and "content newer than baseline" would then be
        // true for a course that had just been published with those very edits in it.
        LocalDateTime now = LocalDateTime.now(clock);

        changes.set(course.getTitle(), request.getTitle().trim(), course::setTitle);
        changes.set(course.getDescription(), request.getDescription(), course::setDescription);
        if (request.carries(CourseRequest.Field.SUBTITLE)) {
            changes.set(course.getSubtitle(), request.getSubtitle(), course::setSubtitle);
        }
        if (request.carries(CourseRequest.Field.IMAGE)) {
            changes.set(course.getImage(), request.getImage(), course::setImage);
        }
        changes.set(course.getStructure(), settings.structure(), course::setStructure);
        changes.set(course.getAccessType(), settings.accessType(), course::setAccessType);
        changes.recordIf(!sameAmount(course.getPurchasePrice(), settings.purchasePrice()));
        course.setPurchasePrice(settings.purchasePrice());

        courseContentSynchronizer.sync(course, request, settings, changes);

        markContentChangedIfNeeded(course, changes, now, "updateCourse", user, courseId);

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
                && request.carries(CourseRequest.Field.IMAGE)
                && request.getImage() != null
                && !previousImage.equals(request.getImage())) {
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
        var course = requireOwnedCourse(user, courseId);

        // Publishing is where the completeness rules bite. An empty draft is a perfectly legal
        // draft; an empty published course is a broken catalogue entry.
        courseValidator.validatePublishable(activeLessonCount(course));

        course.markPublished(LocalDateTime.now(clock));
        log.info("Course published: courseId={} instructorUserId={}", courseId, user.getId());

        return saveAndRespond(course);
    }

    /** Withdraws a course from the catalogue. Content, learners and their history are untouched. */
    @Transactional
    public InstructorCourseResponse unpublish(User user, Long courseId) {
        var course = requireOwnedCourse(user, courseId);
        course.markUnpublished();
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
        var course = requireOwnedCourse(user, courseId);

        List<Long> requestedIds = requireWellFormedOrder(request);
        // Locked for the rest of the transaction: two reorders of one course arriving together
        // would otherwise interleave into an order neither instructor asked for.
        List<CourseModule> modules = courseModuleRepository.findByCourseIdForUpdate(courseId);

        requireCompleteOrder(requestedIds, modules);

        Map<Long, CourseModule> byId = modules.stream()
                .collect(Collectors.toMap(CourseModule::getId, Function.identity()));

        var changes = new CourseContentChanges();
        for (int position = 0; position < requestedIds.size(); position++) {
            CourseModule module = byId.get(requestedIds.get(position));
            changes.set(module.getOrderIndex(), position, module::setOrderIndex);
        }

        // A reorder that asked for the order the course is already in writes nothing and, crucially,
        // does not tell every enrolled learner the course changed.
        markContentChangedIfNeeded(course, changes, LocalDateTime.now(clock), "reorderModules", user, courseId);
        if (changes.hasChanges()) {
            log.info("Course modules reordered: courseId={} instructorUserId={} moduleCount={}",
                    courseId, user.getId(), requestedIds.size());
        }

        return saveAndRespond(course);
    }

    /** Rejects a malformed payload before it is ever compared against the course. */
    private List<Long> requireWellFormedOrder(ModuleOrderRequest request) {
        List<Long> ids = request.getModuleIds();
        if (ids == null) {
            throw new BusinessException("error.course.moduleOrderRequired");
        }
        if (ids.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("error.course.moduleOrderNullId");
        }
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new BusinessException("error.course.moduleOrderDuplicate");
        }
        return ids;
    }

    /**
     * The whole course, exactly once.
     *
     * <p>Set equality, not merely "every id exists": an id from another instructor's course is not
     * in this course's modules and is rejected here, and a list that silently omits a module is
     * rejected too rather than leaving that module stranded at whatever position it happened to
     * hold.
     */
    private void requireCompleteOrder(List<Long> requestedIds, List<CourseModule> modules) {
        Set<Long> owned = modules.stream().map(CourseModule::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> requested = new LinkedHashSet<>(requestedIds);

        List<Long> unknown = new ArrayList<>(requested);
        unknown.removeAll(owned);
        if (!unknown.isEmpty()) {
            throw new BusinessException("error.course.moduleNotInCourse", unknown.get(0));
        }

        if (requested.size() != owned.size()) {
            throw new BusinessException("error.course.moduleOrderIncomplete", owned.size(), requested.size());
        }
    }

    /**
     * Moves the content version — once per request, and only when something really changed.
     *
     * <p>Inside the caller's transaction by construction, so the timestamp and the change it
     * describes commit or roll back together. A save that turns out to be a no-op leaves the
     * previous value alone, which is what stops re-submitting a course unchanged from announcing a
     * new version to its learners.
     */
    private void markContentChangedIfNeeded(Course course, CourseContentChanges changes, LocalDateTime at,
                                            String operation, User user, Long courseId) {
        if (!changes.hasChanges()) {
            return;
        }
        course.markContentChanged(at);
        log.info("Course content changed: courseId={} instructorUserId={} operation={} status={} "
                        + "hasUpdatesSincePublish={}",
                courseId, user.getId(), operation, course.getStatus(), course.hasUpdatesSincePublish());
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

    /** {@code null}-safe numeric equality, so 100 and 100.00 are not a price change. */
    private boolean sameAmount(java.math.BigDecimal left, java.math.BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
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
        if (user.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.course.onlyInstructor");
        }
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (!course.getInstructor().getUser().getId().equals(user.getId())) {
            throw new BusinessException("error.course.notOwner");
        }
        return course;
    }

    /** Drafts are indistinguishable from a missing course on the learner side. */
    private Course findPublishedCourse(Long courseId) {
        var course = courseRepository.findById(courseId)
                .orElseThrow(() -> new ResourceNotFoundException("error.course.notFound", courseId.toString()));
        if (course.getStatus() != CourseStatus.PUBLISHED) {
            throw new ResourceNotFoundException("error.course.notFound", courseId.toString());
        }
        return course;
    }

    private int activeLessonCount(Course course) {
        return course.getStructure() == CourseStructure.MODULES
                ? lessonRepository.countByCourseIdAndModuleIsNotNull(course.getId())
                : lessonRepository.countByCourseIdAndModuleIsNull(course.getId());
    }
}
