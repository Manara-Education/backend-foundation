package com.manara.backend.course.service;

import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CheckoutResponse;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * The checkout entry point, and the one thing {@link CheckoutProcessor}'s transaction cannot do for
 * itself: recover from losing a race.
 *
 * <p>Two checkouts for the same learner and course, arriving at once with nothing yet in the
 * database, both see no entitlement and both insert one. The unique constraints let exactly one
 * through and roll the other back — correct, but the loser is a learner who clicked once and got an
 * error. Since the winner has by then produced precisely the state the loser was asking for, the
 * answer is to look again: the retry finds the access open and returns it, without charging.
 *
 * <p>The retry sits outside the transaction on purpose. A {@code DataIntegrityViolationException}
 * leaves the persistence context unusable, so recovery has to happen in a fresh one — which is also
 * why this is a separate bean rather than a try/catch inside the processor, where a self-call would
 * bypass the proxy and reuse the broken transaction.
 *
 * <p>Exactly one retry. A second failure is not a race any more, and pretending otherwise would loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CourseCheckoutService {

    private final CheckoutProcessor checkoutProcessor;

    public CheckoutResponse checkout(User user, Long courseId, CheckoutRequest request) {
        try {
            return checkoutProcessor.checkout(user, courseId, request);
        } catch (DataIntegrityViolationException concurrentCheckout) {
            log.debug("Concurrent checkout for course {} settled by the database; re-reading", courseId,
                    concurrentCheckout);
            return checkoutProcessor.checkout(user, courseId, request);
        }
    }
}
