package com.manara.backend.lesson.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LessonDetailsResponse {

    private LessonResponse lesson;
    private LessonRef previous;
    private LessonRef next;

    @Getter
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class LessonRef {
        private Long id;
        private String title;
    }
}
