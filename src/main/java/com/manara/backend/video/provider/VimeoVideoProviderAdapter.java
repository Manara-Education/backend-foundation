package com.manara.backend.video.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.manara.backend.video.model.VideoMetadata;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vimeo.
 *
 * <p>Two things make Vimeo different from YouTube, and both are contained here rather than leaking
 * into the lesson domain:
 *
 * <ul>
 *   <li>A Vimeo link is not one path segment. The numeric id can sit under a channel, a group, an
 *       album or a bare path, so the id is looked for wherever Vimeo is known to put it instead of
 *       being assumed to be the first segment.
 *   <li>An unlisted video carries a token — a second path segment, or {@code ?h=} — without which
 *       the embed refuses to play. It is parsed, kept, and put back into the embed URL.
 * </ul>
 *
 * <p>Metadata comes from Vimeo's public oEmbed endpoint, which returns both duration and thumbnail
 * and needs no API key, token or registered application. That is the whole reason this adapter
 * needs no configuration: the alternative, the Vimeo REST API, would put credentials and a rate
 * limit in front of saving a lesson to learn the same two values.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VimeoVideoProviderAdapter implements VideoProviderAdapter {

    private static final Set<String> HOSTS = Set.of("vimeo.com", "player.vimeo.com");

    private static final String OEMBED_ENDPOINT = "https://vimeo.com/api/oembed.json";

    /** Vimeo ids are numeric. Length is not fixed and grows over time, so it is not constrained. */
    private static final String ID = "(\\d+)";

    /** The unlisted-link token: hexadecimal, and always the segment after the id. */
    private static final String HASH = "([A-Za-z0-9]+)";

    /**
     * Where a video id can appear in a Vimeo path, most specific first. The player form is listed
     * before the bare form because {@code /video/123} would otherwise be read by the last pattern
     * with {@code video} as a channel name.
     */
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("^/video/" + ID + "(?:/" + HASH + ")?"),
            Pattern.compile("^/channels/[^/]+/" + ID + "(?:/" + HASH + ")?"),
            Pattern.compile("^/groups/[^/]+/videos/" + ID + "(?:/" + HASH + ")?"),
            Pattern.compile("^/(?:album|showcase)/[^/]+/video/" + ID + "(?:/" + HASH + ")?"),
            Pattern.compile("^/" + ID + "(?:/" + HASH + ")?/?$"));

    private final RestClient videoMetadataRestClient;

    @Override
    public VideoProvider provider() {
        return VideoProvider.VIMEO;
    }

    @Override
    public boolean supports(URI uri) {
        return HOSTS.contains(VideoUris.bareHost(uri));
    }

    @Override
    public Optional<VideoReference> parse(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();

        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(path);
            if (!matcher.find()) continue;

            // A token in the path wins over one in the query: both name the same video, and the
            // path form is the one Vimeo's own share dialog produces.
            String hash = matcher.groupCount() >= 2 ? matcher.group(2) : null;
            if (hash == null) hash = VideoUris.queryParam(uri, "h");

            return Optional.of(new VideoReference(matcher.group(1), hash));
        }
        return Optional.empty();
    }

    @Override
    public String canonicalUrl(VideoReference reference) {
        String base = "https://vimeo.com/" + reference.externalId();
        return reference.hasPrivacyHash() ? base + "/" + reference.privacyHash() : base;
    }

    @Override
    public String embedUrl(VideoReference reference) {
        String base = "https://player.vimeo.com/video/" + reference.externalId();
        return reference.hasPrivacyHash() ? base + "?h=" + reference.privacyHash() : base;
    }

    /**
     * Null by design: Vimeo thumbnails live on a CDN under a content hash, so there is no address
     * to derive from an id. One is fetched by {@link #fetchMetadata} and stored on the lesson, and
     * until that returns the player falls back to its placeholder.
     */
    @Override
    public String thumbnailUrl(VideoReference reference) {
        return null;
    }

    @Override
    public VideoMetadata fetchMetadata(VideoReference reference, String sourceUrl) {
        // The oEmbed endpoint is asked about the canonical URL rather than the pasted one: both
        // identify the same video, but the canonical form carries the unlisted token in the shape
        // the endpoint expects and none of the tracking parameters a shared link collects.
        String target = UriComponentsBuilder.fromUriString(OEMBED_ENDPOINT)
                .queryParam("url", canonicalUrl(reference))
                .build()
                .toUriString();
        try {
            OEmbedResponse body = videoMetadataRestClient.get()
                    .uri(target)
                    .retrieve()
                    .body(OEmbedResponse.class);
            if (body == null) return VideoMetadata.empty();

            return new VideoMetadata(
                    body.duration() != null && body.duration() > 0 ? body.duration() : null,
                    body.thumbnailUrl());
        } catch (RestClientException e) {
            // A private, deleted, or embedding-disabled video answers 403/404 here. That is a fact
            // about the video, not a failure of the save, so it is logged and forgotten.
            log.warn("Failed to read Vimeo metadata for videoId={}: {}",
                    reference.externalId(), e.getMessage());
            return VideoMetadata.empty();
        }
    }

    /**
     * The two fields of Vimeo's oEmbed document Manara has a use for.
     *
     * <p>Bound to a record rather than read out of a generic tree so the shape this adapter depends
     * on is written down: if Vimeo stops sending one of these, the field is null here and the
     * metadata is simply incomplete, which is the same outcome as a failed request.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OEmbedResponse(Integer duration, @JsonProperty("thumbnail_url") String thumbnailUrl) {
    }
}
