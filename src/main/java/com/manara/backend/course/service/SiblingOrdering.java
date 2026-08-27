package com.manara.backend.course.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each member of an ordered sibling collection ends up after an aggregate save.
 *
 * <h2>Why the payload's array order is not the answer</h2>
 * It used to be. Every module and lesson took its position from its index in the submitted array,
 * which reads as the obvious thing to do right up until two tabs are open. A save carries the whole
 * course, so a tab that loaded the course an hour ago is holding an hour-old ordering — and the
 * moment its owner corrects a typo and presses save, that hour-old ordering is written back over
 * whatever anyone reordered in the meantime. The drag was persisted, acknowledged, visible on
 * reload, and then silently undone by an edit that had nothing to do with it.
 *
 * <p>Dedicated order commands now exist for all three ordered scopes a course has — its modules,
 * a flat course's root lessons, and one module's lessons — so an instructor who means to reorder
 * something has a way to say exactly that. Which frees the aggregate save from having to guess:
 * content it is merely editing keeps the order the database already has, and only a real order
 * command moves it.
 *
 * <h2>What still has to be decided here</h2>
 * Two things, and they are why this is not simply "ignore the array".
 *
 * <ul>
 *   <li><strong>New siblings need a position.</strong> A lesson added between the second and third
 *       ones should land between the second and third ones. The payload is the only thing that
 *       knows that, and — crucially — it is not stale about it: the instructor built that
 *       arrangement in the tab that is submitting it. So a new sibling is placed immediately after
 *       whichever persisted sibling precedes it in the payload, and several in a row keep the
 *       order they were added in.
 *   <li><strong>Deletions have to close the gap.</strong> Positions are contiguous by
 *       construction, so removing the second of four leaves three at 0, 1, 2 rather than a hole at
 *       1 that the next reorder would have to reason about.
 * </ul>
 *
 * <p>A lesson moving between modules counts as new to the module it arrives in — it has no
 * position there yet, and the one it held under its old parent means nothing under the new one.
 *
 * <h2>What this deliberately does not do</h2>
 * It does not merge two orderings, and it does not try to detect which of them is newer. A stale
 * array simply has no say: every sibling that already exists keeps its stored relative order, so
 * a save built on an old copy of the course cannot reorder anything, no matter how old it is.
 */
final class SiblingOrdering {

    private SiblingOrdering() {
    }

    /**
     * One sibling as the payload presented it, paired with the position it already holds.
     *
     * @param storedPosition its position in the database, or {@code null} when it does not have one
     *                       in this scope yet — a newly created sibling, or one arriving from
     *                       another parent
     */
    record Slot<T>(T entity, Integer storedPosition) {

        /** A sibling that is already stored in this scope, at {@code storedPosition}. */
        static <T> Slot<T> stored(T entity, Integer storedPosition) {
            return new Slot<>(entity, storedPosition);
        }

        /** A sibling with no position in this scope yet, to be placed from the payload. */
        static <T> Slot<T> unplaced(T entity) {
            return new Slot<>(entity, null);
        }

        boolean isStored() {
            return storedPosition != null;
        }
    }

    /**
     * Resolves the payload-ordered slots into the sequence the scope should be stored in.
     *
     * <p>The result names every entity exactly once. Its indices are the positions to write.
     */
    static <T> List<T> resolve(List<Slot<T>> slots) {
        // Each unplaced sibling hangs off the last stored one that precedes it in the payload;
        // -1 stands for "before every stored sibling", which is where a list that begins with a
        // new lesson puts it.
        Map<Integer, List<T>> unplacedByAnchor = new LinkedHashMap<>();
        List<Integer> storedSlotIndices = new ArrayList<>();

        int anchor = -1;
        for (int i = 0; i < slots.size(); i++) {
            Slot<T> slot = slots.get(i);
            if (slot.isStored()) {
                storedSlotIndices.add(i);
                anchor = i;
            } else {
                unplacedByAnchor.computeIfAbsent(anchor, key -> new ArrayList<>()).add(slot.entity());
            }
        }

        // Stored siblings in the order the database holds them, not the order the payload claimed.
        // The payload index breaks ties so a scope whose positions are not yet unique — rows
        // written before the ordering constraints existed — still resolves to a stable sequence.
        storedSlotIndices.sort(Comparator
                .<Integer, Integer>comparing(i -> slots.get(i).storedPosition())
                .thenComparing(Comparator.naturalOrder()));

        List<T> resolved = new ArrayList<>(slots.size());
        resolved.addAll(unplacedByAnchor.getOrDefault(-1, List.of()));
        for (Integer slotIndex : storedSlotIndices) {
            resolved.add(slots.get(slotIndex).entity());
            resolved.addAll(unplacedByAnchor.getOrDefault(slotIndex, List.of()));
        }
        return resolved;
    }
}
