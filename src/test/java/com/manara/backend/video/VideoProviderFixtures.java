package com.manara.backend.video;

import com.manara.backend.video.provider.VimeoVideoProviderAdapter;
import com.manara.backend.video.provider.YouTubeVideoProviderAdapter;
import com.manara.backend.video.service.VideoProviderResolver;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * A real resolver, wired to the real adapters, for tests of everything upstream of it.
 *
 * <p>Real rather than mocked on purpose. Every URL a course or lesson test uses then goes through
 * the same parsing production does, so a test that saves a lesson proves the URL was genuinely
 * accepted rather than that a stub was told to accept it.
 *
 * <p>The HTTP client the adapters hold is never used here: it backs {@code fetchMetadata} alone,
 * which is called off the request thread and is stubbed out in the tests that care about it.
 */
public final class VideoProviderFixtures {

    private VideoProviderFixtures() {
    }

    public static VideoProviderResolver resolver() {
        RestClient unusedClient = RestClient.create();
        return new VideoProviderResolver(List.of(
                new YouTubeVideoProviderAdapter(unusedClient),
                new VimeoVideoProviderAdapter(unusedClient)));
    }
}
