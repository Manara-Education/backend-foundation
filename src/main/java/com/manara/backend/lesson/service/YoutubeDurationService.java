package com.manara.backend.lesson.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.lesson.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class YoutubeDurationService {

    private static final Pattern LENGTH_SECONDS_PATTERN = Pattern.compile("\"lengthSeconds\":\"(\\d+)\"");

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;
    private final RestClient youtubeRestClient;

    @Async
    @Transactional
    public void fetchAndUpdateDurationAsync(Long lessonId, String videoUrl) {
        if (lessonId == null || videoUrl == null || videoUrl.isBlank()) return;

        fetchDurationSeconds(videoUrl)
                .ifPresent(duration -> updateLessonAndCourseDuration(lessonId, duration));
    }

    private Optional<Integer> fetchDurationSeconds(String videoUrl) {
        try {
            String body = youtubeRestClient.get()
                    .uri(videoUrl)
                    .retrieve()
                    .body(String.class);
            if (body == null) return Optional.empty();

            Matcher matcher = LENGTH_SECONDS_PATTERN.matcher(body);
            if (!matcher.find()) return Optional.empty();

            int seconds = Integer.parseInt(matcher.group(1));
            return seconds > 0 ? Optional.of(seconds) : Optional.empty();
        } catch (RestClientException | NumberFormatException e) {
            log.warn("Failed to fetch YouTube duration for videoUrl={}: {}", videoUrl, e.getMessage());
            return Optional.empty();
        }
    }

    private void updateLessonAndCourseDuration(Long lessonId, int durationSeconds) {
        lessonRepository.findById(lessonId).ifPresent(lesson -> {
            lesson.setDuration(durationSeconds);
            lessonRepository.saveAndFlush(lesson);

            Course course = lesson.getCourse();
            course.setDuration(lessonRepository.sumDurationByCourseId(course.getId()));
            courseRepository.save(course);
        });
    }
}