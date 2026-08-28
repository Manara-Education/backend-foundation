package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.course.model.CourseModule;
import com.manara.backend.course.service.CourseContentChanges;
import com.manara.backend.course.service.SiblingOrdering;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Where a lesson sits among its siblings, for the endpoints that edit one lesson at a time.
 *
 * <h2>Position is the server's to decide</h2>
 * It was the client's, and it could not be. {@code V6} made {@code (course_id, module_id,
 * order_index)} unique, the aggregate save was taught to derive positions, and the standalone
 * endpoints were not — they wrote whatever {@code orderIndex} arrived. Since the field was also
 * mandatory, the natural request ("add a lesson at the top", {@code orderIndex: 0}) was a
 * duplicate-key {@code 409}, there was no way to express "put it at the end", and two clients
 * adding at once could only ever be settled by the database refusing one of them. The client was
 * being asked to compute a value only the server knows, against a rule only the server enforces.
 *
 * <p>Now {@code orderIndex} is optional and means what a human would expect:
 *
 * <ul>
 *   <li><strong>Omitted</strong> — append. The lesson goes at the end of its sibling scope.
 *   <li><strong>Given</strong> — insert there, and everything from that position down moves one
 *       place along. {@code A B C} with {@code D} at 1 becomes {@code A D B C}, not an error.
 *   <li><strong>Out of range</strong> — refused with {@link ErrorCode#INVALID_LESSON_POSITION} and
 *       a message that says what the range is, rather than a constraint name.
 * </ul>
 *
 * <h2>One ordering algorithm</h2>
 * The arrangement itself is {@link SiblingOrdering}'s, the same one the aggregate save uses: the
 * scope is read in the order the database holds it, the arriving lesson is dropped in at the
 * requested index, and the result is written back as a contiguous {@code 0..n-1} run. Two
 * implementations of "close the gap" would eventually disagree about what a gap is.
 *
 * <h2>Concurrency</h2>
 * Every method here reads its scope with a row lock, and the caller has already locked the course
 * row — always in that order, matching the ordering commands — so two lessons added to the same
 * module at once are placed one after the other rather than racing for the same index. The unique
 * constraint stays the backstop it was designed to be, and stops being how ordinary requests fail.
 */
@Component
@RequiredArgsConstructor
public class LessonPlacement {

    private final LessonRepository lessonRepository;

    /**
     * Puts a lesson into a scope it is not part of yet, and rewrites that scope contiguously.
     *
     * <p>A lesson being created, or one arriving from another module. Both are new here, so a
     * request that names no position appends: that is what "add a lesson" means, and the position
     * it held under its old parent means nothing under this one.
     *
     * @param requestedIndex the zero-based position asked for, or {@code null} to append
     * @return the position the lesson ended up at
     */
    public int insert(Long courseId, CourseModule module, Lesson arriving, Integer requestedIndex,
                      CourseContentChanges changes) {
        List<Lesson> siblings = othersInScope(courseId, module, arriving);
        return placeAt(siblings, arriving, requirePosition(requestedIndex, siblings.size()), changes);
    }

    /**
     * Moves a lesson that is already in this scope, and rewrites the scope contiguously.
     *
     * <p>A request that names no position leaves it exactly where it is. That distinction is the
     * whole difference between this and {@link #insert}: an edit that only renames a lesson must
     * not also move it to the end of its module.
     *
     * @param requestedIndex the zero-based position asked for, or {@code null} to stay put
     * @return the position the lesson ended up at
     */
    public int reposition(Long courseId, CourseModule module, Lesson arriving, Integer requestedIndex,
                          CourseContentChanges changes) {
        List<Lesson> siblings = othersInScope(courseId, module, arriving);
        int stay = arriving.getOrderIndex() == null
                ? siblings.size()
                : Math.min(arriving.getOrderIndex(), siblings.size());
        int position = requestedIndex == null ? stay : requirePosition(requestedIndex, siblings.size());
        return placeAt(siblings, arriving, position, changes);
    }

    /**
     * Closes the gap a lesson left behind — after a delete, or after it moved to another module.
     *
     * <p>Costs nothing when there is no gap: every sibling is already at the position this would
     * write, so nothing is recorded and the course's content version does not move.
     */
    public void compact(Long courseId, CourseModule module, CourseContentChanges changes) {
        List<SiblingOrdering.Slot<Lesson>> slots = storedSlots(scopeForUpdate(courseId, module));
        applyPositions(slots, changes);
    }

    private int placeAt(List<Lesson> siblings, Lesson arriving, int position,
                        CourseContentChanges changes) {
        List<SiblingOrdering.Slot<Lesson>> slots = storedSlots(siblings);
        // Unplaced, not stored at `position`: the arriving lesson has no position among these
        // siblings, which is exactly the case SiblingOrdering was written to decide. Putting it into
        // the list at `position` is how the request says where it goes.
        slots.add(position, SiblingOrdering.Slot.unplaced(arriving));

        applyPositions(slots, changes);
        return arriving.getOrderIndex();
    }

    private List<SiblingOrdering.Slot<Lesson>> storedSlots(List<Lesson> siblings) {
        List<SiblingOrdering.Slot<Lesson>> slots = new ArrayList<>(siblings.size() + 1);
        for (Lesson sibling : siblings) {
            slots.add(SiblingOrdering.Slot.stored(sibling, sibling.getOrderIndex()));
        }
        return slots;
    }

    /**
     * The scope, locked, with the lesson being placed taken out of it.
     *
     * <p>Excluded rather than left in, so moving a lesson from position 3 to position 0 is the same
     * operation as inserting a new one there — and its own stale position never anchors the
     * arrangement it is being moved out of. A newly created lesson is already in the table by the
     * time this runs, carrying a provisional position, and is filtered out here for the same reason.
     */
    private List<Lesson> othersInScope(Long courseId, CourseModule module, Lesson arriving) {
        return scopeForUpdate(courseId, module).stream()
                .filter(sibling -> arriving.getId() == null
                        || !Objects.equals(sibling.getId(), arriving.getId()))
                .toList();
    }

    /**
     * The requested position, or the end of the scope when none was asked for.
     *
     * <p>{@code size} is a legal answer, not one past the end: appending to a scope of three means
     * position 3. The message counts from one because the instructor does.
     */
    private int requirePosition(Integer requestedIndex, int size) {
        if (requestedIndex == null) {
            return size;
        }
        if (requestedIndex < 0 || requestedIndex > size) {
            throw new BusinessException(ErrorCode.INVALID_LESSON_POSITION,
                    "error.course.lessonPositionInvalid", size + 1, requestedIndex + 1);
        }
        return requestedIndex;
    }

    private List<Lesson> scopeForUpdate(Long courseId, CourseModule module) {
        return module == null
                ? lessonRepository.findRootLessonsForUpdate(courseId)
                : lessonRepository.findModuleLessonsForUpdate(courseId, module.getId());
    }

    private void applyPositions(List<SiblingOrdering.Slot<Lesson>> slots, CourseContentChanges changes) {
        List<Lesson> resolved = SiblingOrdering.resolve(slots);
        for (int position = 0; position < resolved.size(); position++) {
            Lesson lesson = resolved.get(position);
            // The weakest description there is, so a sibling that only shifted along because
            // something was inserted above it keeps whatever stronger thing was already said about
            // it — a lesson created by this very request stays "new".
            int current = lesson.getOrderIndex() == null ? -1 : lesson.getOrderIndex();
            changes.of(lesson).reordered(current, position, lesson::setOrderIndex);
        }
    }
}
