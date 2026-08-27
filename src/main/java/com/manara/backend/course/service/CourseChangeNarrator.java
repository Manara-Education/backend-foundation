package com.manara.backend.course.service;

import com.manara.backend.common.service.MessageService;
import com.manara.backend.course.model.ContentChangeType;
import com.manara.backend.course.model.ContentEntityType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns a recorded change into a sentence a learner can read.
 *
 * <p>The one place the database's vocabulary becomes the product's. A student is never shown
 * {@code CONTENT_UPDATED} or {@code entityType=LESSON}; they are shown "Lesson content updated", in
 * Arabic or English according to the request's {@code Accept-Language}, through the same
 * {@link MessageService} every other user-facing string in the application goes through.
 *
 * <p>Keeping it server-side rather than shipping the enum and letting the client phrase it means
 * the two clients cannot word the same change differently, and a new change type cannot reach a
 * screen that has no word for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseChangeNarrator {

    private static final String PREFIX = "course.change.";

    private final MessageService messageService;

    /**
     * @param fromLabel for {@link ContentChangeType#MOVED}, the parent the item left
     * @param toLabel   for {@link ContentChangeType#MOVED}, the parent it sits under now
     * @return the sentence, or {@code null} if this pairing has no wording — a missing phrase must
     *         degrade to a bare "Updated" badge, never to a failed course page
     */
    public String describe(ContentEntityType entityType, ContentChangeType changeType,
                           String fromLabel, String toLabel) {
        if (entityType == null || changeType == null) {
            return null;
        }
        if (changeType == ContentChangeType.MOVED) {
            return moved(entityType, fromLabel, toLabel);
        }
        return lookup(PREFIX + entityType + "." + changeType);
    }

    /**
     * "Moved from X to Y" needs both ends, and only one of them is recorded. The other is read from
     * the tree as it stands now — so a lesson whose new parent has since been deleted, or one moved
     * out to the root of a flat course, falls back to naming only where it came from rather than
     * printing an empty destination.
     */
    private String moved(ContentEntityType entityType, String fromLabel, String toLabel) {
        if (fromLabel == null) {
            return lookup(PREFIX + entityType + ".MOVED_UNKNOWN");
        }
        return toLabel == null
                ? lookup(PREFIX + entityType + ".MOVED_FROM", fromLabel)
                : lookup(PREFIX + entityType + ".MOVED", fromLabel, toLabel);
    }

    private String lookup(String code, Object... args) {
        try {
            return messageService.get(code, args);
        } catch (RuntimeException noSuchMessage) {
            // A course page that renders without a caption is a small loss; one that 500s because
            // somebody added an enum constant and not a translation is a large one.
            log.warn("No wording for course change: code={}", code);
            return null;
        }
    }
}
