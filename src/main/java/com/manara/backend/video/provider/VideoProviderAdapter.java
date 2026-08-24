package com.manara.backend.video.provider;

import com.manara.backend.video.model.VideoMetadata;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoReference;

import java.net.URI;
import java.util.Optional;

/**
 * Everything Manara needs to know about one video platform, and the only place it is allowed to
 * know it.
 *
 * <p>A new platform is a new implementation of this interface annotated as a Spring component:
 * {@link com.manara.backend.video.service.VideoProviderResolver} collects them all, so nothing
 * else — no course service, no lesson service, no controller, no mapper — has to be edited to
 * teach the product about it.
 *
 * <p>Implementations must be stateless and cheap to call for everything except
 * {@link #fetchMetadata(VideoReference, String)}, which is the only method allowed to touch the
 * network. The rest run inside request handling and on the read path.
 */
public interface VideoProviderAdapter {

    /** The platform this adapter speaks for. Unique across adapters. */
    VideoProvider provider();

    /**
     * Whether this URL belongs to this platform, judged on host alone.
     *
     * <p>Kept separate from {@link #parse(URI)} so the resolver can tell "this is a Vimeo link we
     * could not read" from "this is not a video link we support", and report the difference. Host
     * matching is exact — a substring test would accept {@code youtube.com.example.com}.
     */
    boolean supports(URI uri);

    /**
     * The video this URL points at, or empty when the host matches but the path does not name a
     * video this platform would recognise.
     */
    Optional<VideoReference> parse(URI uri);

    /** The tidy public address of this video — what the platform itself would link to. */
    String canonicalUrl(VideoReference reference);

    /**
     * The address an iframe is pointed at, carrying only what playback cannot work without.
     *
     * <p>Player options are deliberately absent: they differ per surface and the {@code origin}
     * one can only be filled in by the browser that renders the frame, so the client appends its
     * own. Returning a half-configured URL here would have every client strip it again.
     */
    String embedUrl(VideoReference reference);

    /**
     * A still image at an address derivable from the id alone, or null when this platform only
     * gives one up through {@link #fetchMetadata}.
     */
    String thumbnailUrl(VideoReference reference);

    /**
     * Asks the platform what it knows about the video. Called off the request thread.
     *
     * <p>Implementations must never throw: a provider that is down, slow, or has removed the video
     * returns {@link VideoMetadata#empty()}, because none of that should fail the save that
     * triggered the lookup.
     *
     * @param sourceUrl the URL as stored, for platforms whose metadata endpoint takes a URL rather
     *                  than an id — an unlisted video needs the token that only the URL carries
     */
    VideoMetadata fetchMetadata(VideoReference reference, String sourceUrl);
}
