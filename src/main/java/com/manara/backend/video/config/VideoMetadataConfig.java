package com.manara.backend.video.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client the provider adapters use to ask a platform about a video.
 *
 * <p>One client for every provider, because they all want the same thing from it: short timeouts,
 * so a slow or unreachable platform occupies an async worker for seconds rather than minutes, and
 * a browser user agent, which YouTube's watch page requires before it will return the markup the
 * running time is read from.
 *
 * <p>This replaces {@code LessonConfig}, whose only content was the YouTube-named version of this
 * bean. It lives under {@code video} now because it belongs to the providers, not to lessons.
 */
@Configuration
public class VideoMetadataConfig {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient videoMetadataRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) TIMEOUT.toMillis());
        factory.setReadTimeout((int) TIMEOUT.toMillis());

        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", "Mozilla/5.0")
                .build();
    }
}
