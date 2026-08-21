package com.manara.backend.course.service;

import com.manara.backend.course.dto.CheckoutRequest;
import com.manara.backend.course.dto.CheckoutResponse;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Losing the race, and recovering from it.
 *
 * <p>Two checkouts for the same learner and course can both find nothing in the database and both
 * try to insert. The unique constraints settle it, and the loser's job is not to show an error but
 * to look again — by then the winner has produced exactly the state it was asking for.
 */
@ExtendWith(MockitoExtension.class)
class CourseCheckoutServiceTest {

    private static final Long COURSE_ID = 7L;

    @Mock
    private CheckoutProcessor checkoutProcessor;

    @InjectMocks
    private CourseCheckoutService courseCheckoutService;

    private final User studentUser = User.builder().id(2L).role(Role.STUDENT).build();
    private final CheckoutRequest request = new CheckoutRequest();

    @Test
    void aCheckoutThatSucceedsIsPassedStraightThrough() {
        CheckoutResponse expected = CheckoutResponse.builder().courseId(COURSE_ID).build();
        given(checkoutProcessor.checkout(studentUser, COURSE_ID, request)).willReturn(expected);

        assertThat(courseCheckoutService.checkout(studentUser, COURSE_ID, request)).isSameAs(expected);
        verify(checkoutProcessor, times(1)).checkout(studentUser, COURSE_ID, request);
    }

    @Test
    void aCheckoutThatLosesTheRaceIsRetriedOnceAndAnswersWithTheWinnersState() {
        CheckoutResponse settled = CheckoutResponse.builder().courseId(COURSE_ID).build();
        given(checkoutProcessor.checkout(studentUser, COURSE_ID, request))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"))
                .willReturn(settled);

        assertThat(courseCheckoutService.checkout(studentUser, COURSE_ID, request)).isSameAs(settled);
        verify(checkoutProcessor, times(2)).checkout(studentUser, COURSE_ID, request);
    }

    /** A second failure is not a race any more, and retrying forever would hide a real fault. */
    @Test
    void aSecondConflictIsNotRetriedAgain() {
        given(checkoutProcessor.checkout(studentUser, COURSE_ID, request))
                .willThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> courseCheckoutService.checkout(studentUser, COURSE_ID, request))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(checkoutProcessor, times(2)).checkout(studentUser, COURSE_ID, request);
    }
}
