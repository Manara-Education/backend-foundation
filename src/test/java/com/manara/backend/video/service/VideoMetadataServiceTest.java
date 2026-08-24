package com.manara.backend.video.service;

import com.manara.backend.course.model.Course;
import com.manara.backend.course.repository.CourseRepository;
import com.manara.backend.lesson.model.Lesson;
import com.manara.backend.lesson.repository.LessonRepository;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.provider.VimeoVideoProviderAdapter;
import com.manara.backend.video.provider.YouTubeVideoProviderAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Duration and thumbnails, which are the two things only the provider knows.
 *
 * <p>What these tests are really about is that a Vimeo lesson and a YouTube lesson come out the
 * other side identical: the same {@code lessons.duration} column written, the same course total
 * recomputed. Everything downstream — progress, curriculum, the course card's "12 hours" — reads
 * those, so proving they are filled the same way for both providers is what makes course duration
 * provider-independent rather than merely provider-aware.
 */
class VideoMetadataServiceTest {

    private static final Long LESSON_ID = 7L;
    private static final Long COURSE_ID = 3L;

    private LessonRepository lessonRepository;
    private CourseRepository courseRepository;
    private MockRestServiceServer server;
    private VideoMetadataService service;

    private Course course;

    @BeforeEach
    void setUp() {
        lessonRepository = mock(LessonRepository.class);
        courseRepository = mock(CourseRepository.class);

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient client = builder.build();

        VideoProviderResolver resolver = new VideoProviderResolver(List.of(
                new YouTubeVideoProviderAdapter(client),
                new VimeoVideoProviderAdapter(client)));

        service = new VideoMetadataService(lessonRepository, courseRepository, resolver);

        course = Course.builder().id(COURSE_ID).title("Course").duration(0).build();
    }

    private Lesson lessonWith(String url) {
        Lesson lesson = Lesson.builder()
                .id(LESSON_ID)
                .title("Lesson")
                .video(VideoSource.ofUrl(url))
                .duration(0)
                .orderIndex(0)
                .course(course)
                .build();
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.of(lesson));
        return lesson;
    }

    @Test
    void readsAYouTubeDurationFromTheWatchPageAndRollsItIntoTheCourseTotal() {
        Lesson lesson = lessonWith("https://youtu.be/dQw4w9WgXcQ");
        given(lessonRepository.sumDurationByCourseId(COURSE_ID)).willReturn(212);

        server.expect(requestTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andRespond(withSuccess("""
                        <html><script>{"videoDetails":{"lengthSeconds":"212"}}</script></html>
                        """, MediaType.TEXT_HTML));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        server.verify();
        assertThat(lesson.getDuration()).isEqualTo(212);
        assertThat(course.getDuration()).isEqualTo(212);
        verify(courseRepository).save(course);
    }

    /**
     * The same outcome by a different route: Vimeo answers a JSON document rather than a web page,
     * and the lesson ends up in exactly the state the YouTube case leaves it in.
     */
    @Test
    void readsAVimeoDurationFromOEmbedAndRollsItIntoTheCourseTotal() {
        Lesson lesson = lessonWith("https://vimeo.com/76979871");
        given(lessonRepository.sumDurationByCourseId(COURSE_ID)).willReturn(597);

        server.expect(requestTo("https://vimeo.com/api/oembed.json?url=https://vimeo.com/76979871"))
                .andRespond(withSuccess("""
                        {"duration":597,"thumbnail_url":"https://i.vimeocdn.com/video/abc_640.jpg"}
                        """, MediaType.APPLICATION_JSON));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        server.verify();
        assertThat(lesson.getDuration()).isEqualTo(597);
        assertThat(course.getDuration()).isEqualTo(597);
        verify(courseRepository).save(course);
    }

    /**
     * Vimeo is the reason the thumbnail is stored at all: unlike YouTube's, its address cannot be
     * derived from the id, so this lookup is the only chance to learn it.
     */
    @Test
    void storesTheThumbnailVimeoReturns() {
        Lesson lesson = lessonWith("https://vimeo.com/76979871");
        given(lessonRepository.sumDurationByCourseId(COURSE_ID)).willReturn(597);

        server.expect(requestTo("https://vimeo.com/api/oembed.json?url=https://vimeo.com/76979871"))
                .andRespond(withSuccess("""
                        {"duration":597,"thumbnail_url":"https://i.vimeocdn.com/video/abc_640.jpg"}
                        """, MediaType.APPLICATION_JSON));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        assertThat(lesson.getVideo().getThumbnailUrl())
                .isEqualTo("https://i.vimeocdn.com/video/abc_640.jpg");
    }

    /** An unlisted video is asked about by the link that can actually see it. */
    @Test
    void asksVimeoAboutAnUnlistedVideoUsingItsToken() {
        Lesson lesson = lessonWith("https://vimeo.com/76979871?h=abc123def4");
        given(lessonRepository.sumDurationByCourseId(COURSE_ID)).willReturn(597);

        server.expect(requestTo(
                        "https://vimeo.com/api/oembed.json?url=https://vimeo.com/76979871/abc123def4"))
                .andRespond(withSuccess("{\"duration\":597}", MediaType.APPLICATION_JSON));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        server.verify();
        assertThat(lesson.getDuration()).isEqualTo(597);
    }

    /**
     * A private, deleted or embedding-disabled video answers with an error. The lesson was already
     * saved; losing its duration is a cosmetic gap, so nothing is written and nothing is raised.
     */
    @Test
    void leavesTheLessonAloneWhenTheProviderRefuses() {
        Lesson lesson = lessonWith("https://vimeo.com/76979871");

        server.expect(requestTo("https://vimeo.com/api/oembed.json?url=https://vimeo.com/76979871"))
                .andRespond(withStatus(org.springframework.http.HttpStatus.NOT_FOUND));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        assertThat(lesson.getDuration()).isZero();
        verify(lessonRepository, never()).saveAndFlush(any(Lesson.class));
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void leavesTheLessonAloneWhenTheProviderIsUnreachable() {
        Lesson lesson = lessonWith("https://youtu.be/dQw4w9WgXcQ");

        server.expect(requestTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andRespond(withServerError());

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        assertThat(lesson.getDuration()).isZero();
        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void leavesTheLessonAloneWhenThePageCarriesNoDuration() {
        Lesson lesson = lessonWith("https://youtu.be/dQw4w9WgXcQ");

        server.expect(requestTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andRespond(withSuccess("<html>nothing useful here</html>", MediaType.TEXT_HTML));

        service.refreshAsync(LESSON_ID, lesson.getVideo());

        assertThat(lesson.getDuration()).isZero();
        verify(courseRepository, never()).save(any(Course.class));
    }

    /** A URL no adapter claims never reaches the network at all. */
    @Test
    void doesNotCallOutForAVideoItCannotPlace() {
        service.refreshAsync(LESSON_ID, VideoSource.ofUrl("https://example.com/video.mp4"));

        server.verify();
        verify(lessonRepository, never()).findById(any());
    }

    @Test
    void doesNothingWithoutALessonOrAUrl() {
        service.refreshAsync(null, VideoSource.ofUrl("https://youtu.be/dQw4w9WgXcQ"));
        service.refreshAsync(LESSON_ID, null);
        service.refreshAsync(LESSON_ID, VideoSource.ofUrl(""));

        server.verify();
        verify(lessonRepository, never()).findById(any());
    }

    /**
     * The lesson may have been re-pointed, or deleted, between the save that scheduled this and its
     * arrival here — so what gets updated is whatever the row says now, not the entity that was
     * handed over.
     */
    @Test
    void survivesTheLessonHavingBeenDeletedInTheMeantime() {
        given(lessonRepository.findById(LESSON_ID)).willReturn(Optional.empty());

        server.expect(requestTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
                .andRespond(withSuccess("{\"lengthSeconds\":\"212\"}", MediaType.TEXT_HTML));

        service.refreshAsync(LESSON_ID, VideoSource.ofUrl("https://youtu.be/dQw4w9WgXcQ"));

        verify(courseRepository, never()).save(any(Course.class));
    }

    @Test
    void describesBothProvidersThroughTheSameEntryPoint() {
        assertThat(VideoProvider.values()).containsExactly(VideoProvider.YOUTUBE, VideoProvider.VIMEO);
    }
}
