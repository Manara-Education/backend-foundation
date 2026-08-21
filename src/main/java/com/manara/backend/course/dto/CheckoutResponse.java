package com.manara.backend.course.dto;

import com.manara.backend.course.model.CourseAccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * What a checkout produced.
 *
 * <p>Returned identically whether the call did the work or merely found it already done — a retried
 * or double-clicked checkout answers with the same body as the one that succeeded, because the state
 * it describes is the same. {@code paymentReference} is null on those repeats: nothing was charged
 * the second time.
 */
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class CheckoutResponse {

    private Long enrollmentId;

    private Long courseId;

    /** How the course is sold — which of the three checkout paths was taken. */
    private CourseAccessType accessType;

    private CourseAccessResponse access;

    /**
     * The gateway's reference for the charge this call made, or {@code null} when nothing was
     * charged — a free course, or an access that already existed.
     *
     * <p>Payments are simulated in this application; references issued by the simulator are prefixed
     * {@code sim_}.
     */
    private String paymentReference;
}
