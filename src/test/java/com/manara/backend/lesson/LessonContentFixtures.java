package com.manara.backend.lesson;

import com.manara.backend.lesson.content.LinkUrlPolicy;
import com.manara.backend.lesson.content.RichContentSanitizer;
import com.manara.backend.lesson.service.LessonContentWriter;
import com.manara.backend.lesson.validation.LessonContentValidator;
import com.manara.backend.video.VideoProviderFixtures;

/**
 * The real content validator and writer, for tests of everything upstream of them.
 *
 * <p>Real rather than mocked, for the same reason {@link VideoProviderFixtures} is: a test that
 * saves a rich-content lesson should prove the document went through the actual sanitizer, not that
 * a stub was told to return it. It is also the only way a test can show that an unsafe URL is
 * refused by the path an instructor would really take.
 */
public final class LessonContentFixtures {

    private LessonContentFixtures() {
    }

    public static RichContentSanitizer sanitizer() {
        return new RichContentSanitizer(new LinkUrlPolicy());
    }

    public static LessonContentValidator validator() {
        return new LessonContentValidator(VideoProviderFixtures.resolver(), sanitizer());
    }

    public static LessonContentWriter writer() {
        return new LessonContentWriter(VideoProviderFixtures.resolver());
    }

    /** A minimal but genuinely meaningful document, as canonical JSON. */
    public static String document(String text) {
        return """
                {"version":1,"blocks":[{"type":"paragraph","content":[{"type":"text","text":"%s"}]}]}"""
                .formatted(text);
    }
}
