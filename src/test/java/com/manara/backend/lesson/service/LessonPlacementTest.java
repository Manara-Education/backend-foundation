package com.manara.backend.lesson.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.course.service.CourseContentChanges;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The refusal of a position that does not exist.
 *
 * <p>The message counts from one because the instructor does, so the position asked for is shown
 * plus one. That addition must not wrap: the largest index a client can send is out of range by
 * definition and reaches exactly that line.
 */
@ExtendWith(MockitoExtension.class)
class LessonPlacementTest {

    private static final long COURSE_ID = 1L;

    @Mock
    private LessonRepository lessonRepository;

    @InjectMocks
    private LessonPlacement lessonPlacement;

    @Test
    @DisplayName("the largest possible index is refused with the position it asked for, not a wrapped negative one")
    void largestIndexIsReportedWithoutOverflow() {
        when(lessonRepository.findRootLessonsForUpdate(COURSE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> lessonPlacement.insert(
                COURSE_ID, null, new Lesson(), Integer.MAX_VALUE, new CourseContentChanges()))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_LESSON_POSITION);
                    assertThat(ex.getArgs()).containsExactly(1, 2_147_483_648L);
                });
    }

    @Test
    @DisplayName("a negative index is still refused with the position it asked for")
    void negativeIndexIsReportedAsAsked() {
        when(lessonRepository.findRootLessonsForUpdate(COURSE_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> lessonPlacement.insert(
                COURSE_ID, null, new Lesson(), -1, new CourseContentChanges()))
                .isInstanceOfSatisfying(BusinessException.class, ex ->
                        assertThat(ex.getArgs()).containsExactly(1, 0L));
    }
}
