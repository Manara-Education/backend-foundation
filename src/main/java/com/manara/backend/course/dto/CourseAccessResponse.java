package com.manara.backend.course.dto;

import com.manara.backend.course.model.AccessStatus;
import com.manara.backend.course.model.EntitlementSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * The viewing learner's standing on one course: whether they joined it, whether they may open it
 * right now, and — for a subscription — how long that lasts.
 *
 * <p>Every field is the server's own answer. A client renders the enrol / buy / subscribe / continue
 * / renew states from this block instead of inferring them from a price being null or a progress
 * figure being non-zero, which is what it used to have to do.
 *
 * <p>For a viewer who is not a learner — a signed-out visitor, an instructor previewing — the block
 * reports {@code enrolled=false}, {@code entitled=false}, {@code status=NONE}.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CourseAccessResponse {

    /** They joined the course. Stays true after a subscription lapses. */
    private Boolean enrolled;

    /** They may be served protected content right now. */
    private Boolean entitled;

    /** What their access rests on, or {@code null} when nothing was ever granted. */
    private EntitlementSource source;

    private AccessStatus status;

    private LocalDateTime startsAt;

    /** {@code null} for a grant that never ends — free courses and outright purchases. */
    private LocalDateTime expiresAt;

    /** Whole days left before {@code expiresAt}, or {@code null} when nothing expires. */
    private Integer daysRemaining;

    /** The plan the current or most recent window was bought under, when there is one. */
    private Long planId;
}
