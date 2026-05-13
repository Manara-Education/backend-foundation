package com.manara.backend.course.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.Lesson;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.course.repository.LessonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.net.URL;
import java.net.URI;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;

@Service
@RequiredArgsConstructor
public class YoutubeDurationService {

    private final LessonRepository lessonRepository;
    private final CourseRepository courseRepository;

    @Async
    @Transactional
    public void fetchAndUpdateDurationAsync(Long lessonId, String videoId) {
        if (videoId == null || videoId.isEmpty()) return;
        try {
            String url = "https://www.youtube.com/watch?v=" + videoId;
            URL obj = URI.create(url).toURL();
            HttpURLConnection con = (HttpURLConnection) obj.openConnection();
            con.setRequestMethod("GET");
            con.setRequestProperty("User-Agent", "Mozilla/5.0");
            con.setConnectTimeout(5000);
            con.setReadTimeout(5000);
            
            Integer duration = 0;
            try (BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()))) {
                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    if (inputLine.contains("\"lengthSeconds\":\"")) {
                        int start = inputLine.indexOf("\"lengthSeconds\":\"") + 17;
                        int end = inputLine.indexOf("\"", start);
                        duration = Integer.parseInt(inputLine.substring(start, end));
                        break;
                    }
                }
            }

            if (duration > 0) {
                Lesson lesson = lessonRepository.findById(lessonId).orElse(null);
                if (lesson != null) {
                    lesson.setDuration(duration);
                    lessonRepository.saveAndFlush(lesson);
                    
                    Course course = lesson.getCourse();
                    Integer newDuration = lessonRepository.sumDurationByCourseId(course.getId());
                    course.setDuration(newDuration);
                    courseRepository.save(course);
                }
            }
        } catch (Exception e) {
            // Silently fail and keep the default duration (which is 0 initially)
        }
    }
}
