package com.manara.backend.video.provider;

import com.manara.backend.video.model.VideoMetadata;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube.
 *
 * <p>This is where the prototype's YouTube handling now lives, unchanged in what it accepts and
 * what it produces. The duration lookup below is the same page scrape the prototype ran, kept
 * deliberately: YouTube's oEmbed endpoint returns a title and a thumbnail but no running time, and
 * the Data API would put an API key and a quota between an instructor and saving a lesson.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class YouTubeVideoProviderAdapter implements VideoProviderAdapter {

    /**
     * Hosts served by YouTube. Matched exactly, after stripping a leading {@code www.} or
     * {@code m.}, so a lookalike domain cannot pass as YouTube.
     */
    private static final Set<String> HOSTS = Set.of(
            "youtube.com", "youtu.be", "youtube-nocookie.com");

    /** A YouTube video id: always eleven characters from this alphabet. */
    private static final String ID = "([A-Za-z0-9_-]{11})";

    /**
     * Every URL shape YouTube hands out. The first four are the ones the prototype's two copies of
     * this list accepted; {@code /v/} and {@code /live/} are added because YouTube still issues
     * them and rejecting a link that plays fine in a browser reads as a bug to an instructor.
     */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("[?&]v=" + ID),
            Pattern.compile("^/" + ID + "$"),
            Pattern.compile("^/embed/" + ID),
            Pattern.compile("^/shorts/" + ID),
            Pattern.compile("^/v/" + ID),
            Pattern.compile("^/live/" + ID));

    private static final Pattern LENGTH_SECONDS = Pattern.compile("\"lengthSeconds\":\"(\\d+)\"");

    /**
     * Injected by type today and by name if a second {@link RestClient} is ever defined; either
     * way it is the video-metadata client, whose short timeouts keep a slow provider from
     * holding an async worker.
     */
    private final RestClient videoMetadataRestClient;

    @Override
    public VideoProvider provider() {
        return VideoProvider.YOUTUBE;
    }

    @Override
    public boolean supports(URI uri) {
        return HOSTS.contains(VideoUris.bareHost(uri));
    }

    @Override
    public Optional<VideoReference> parse(URI uri) {
        // youtu.be puts the id in the path, everything else in `v=` or a named path segment, so
        // both the path and the query are offered to the same list of patterns.
        String path = uri.getPath() == null ? "" : uri.getPath();
        String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

        for (Pattern pattern : PATTERNS) {
            Matcher onPath = pattern.matcher(path);
            if (onPath.find()) return Optional.of(VideoReference.of(onPath.group(1)));

            Matcher onQuery = pattern.matcher(query);
            if (onQuery.find()) return Optional.of(VideoReference.of(onQuery.group(1)));
        }
        return Optional.empty();
    }

    @Override
    public String canonicalUrl(VideoReference reference) {
        return "https://www.youtube.com/watch?v=" + reference.externalId();
    }

    @Override
    public String embedUrl(VideoReference reference) {
        return "https://www.youtube.com/embed/" + reference.externalId();
    }

    @Override
    public String thumbnailUrl(VideoReference reference) {
        return "https://img.youtube.com/vi/" + reference.externalId() + "/hqdefault.jpg";
    }

    /**
     * Reads the running time out of the watch page.
     *
     * <p>The thumbnail is not fetched here — {@link #thumbnailUrl} already knows it — so a page
     * that cannot be reached costs the lesson its duration and nothing else.
     */
    @Override
    public VideoMetadata fetchMetadata(VideoReference reference, String sourceUrl) {
        try {
            String body = videoMetadataRestClient.get()
                    .uri(canonicalUrl(reference))
                    .retrieve()
                    .body(String.class);
            if (body == null) return VideoMetadata.empty();

            Matcher matcher = LENGTH_SECONDS.matcher(body);
            if (!matcher.find()) return VideoMetadata.empty();

            int seconds = Integer.parseInt(matcher.group(1));
            return seconds > 0
                    ? new VideoMetadata(seconds, thumbnailUrl(reference))
                    : VideoMetadata.empty();
        } catch (RestClientException | NumberFormatException e) {
            log.warn("Failed to read YouTube metadata for videoId={}: {}",
                    reference.externalId(), e.getMessage());
            return VideoMetadata.empty();
        }
    }
}
