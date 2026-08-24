package com.manara.backend.video.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoReference;
import com.manara.backend.video.model.VideoSource;
import com.manara.backend.video.provider.VideoProviderAdapter;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The one place a URL becomes a video.
 *
 * <p>Nothing else in the application asks what a video URL looks like. Services, validators,
 * mappers and controllers ask this class, which asks the adapters. That is what keeps the answer
 * to "which provider is this?" identical on the create path, the edit path, the read path and in
 * the tests — the failure mode the prototype had, where the instructor preview and the student
 * player each carried their own copy of the same regexes and could drift apart.
 *
 * <h2>Two ways in, on purpose</h2>
 *
 * <p>{@link #resolve(String)} is the write path and is strict: a URL Manara cannot play is refused
 * before it reaches the database.
 *
 * <p>{@link #describe(VideoSource)} is the read path and never throws. Rows written before this
 * change exist, rows written by a seed or a support fix exist, and a lesson whose URL cannot be
 * parsed must still render — as a lesson with no player, not as a failed request that takes the
 * whole course listing down with it.
 */
@Component
public class VideoProviderResolver {

    private final Map<VideoProvider, VideoProviderAdapter> adapters = new EnumMap<>(VideoProvider.class);

    /**
     * Every adapter Spring can find, indexed by provider. A provider added later is picked up here
     * with no edit to this class.
     */
    public VideoProviderResolver(List<VideoProviderAdapter> adapters) {
        for (VideoProviderAdapter adapter : adapters) {
            VideoProviderAdapter previous = this.adapters.put(adapter.provider(), adapter);
            if (previous != null) {
                throw new IllegalStateException(
                        "Two adapters claim provider " + adapter.provider() + ": "
                                + previous.getClass().getName() + " and " + adapter.getClass().getName());
            }
        }
    }

    public Optional<VideoProviderAdapter> adapterFor(VideoProvider provider) {
        return Optional.ofNullable(adapters.get(provider));
    }

    /**
     * Understands a URL, or explains why it cannot.
     *
     * <p>The three failures are told apart because they mean different things to an instructor: a
     * typo, a platform Manara does not play, and a link to the right platform that does not name a
     * video. Each carries its own message; none of them names an adapter, a regex or a host list.
     *
     * @throws BusinessException when the URL is blank, malformed, hosted somewhere unsupported, or
     *                           carries no video id
     */
    public ResolvedVideo resolve(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BusinessException("error.video.urlRequired");
        }
        String url = rawUrl.trim();

        URI uri = parseUri(url).orElseThrow(() -> new BusinessException("error.video.urlMalformed"));

        VideoProviderAdapter adapter = adapters.values().stream()
                .filter(candidate -> candidate.supports(uri))
                .findFirst()
                .orElseThrow(() -> new BusinessException("error.video.providerUnsupported"));

        VideoReference reference = adapter.parse(uri)
                .orElseThrow(() -> new BusinessException("error.video.videoIdInvalid"));

        return describe(adapter, url, reference);
    }

    /**
     * Resolves a URL and, when the client volunteered a provider, refuses the pair if it does not
     * describe what the URL actually points at.
     *
     * <p>The URL wins — it is the thing that gets played. A mismatch is rejected outright rather
     * than quietly corrected, because the two halves disagreeing means the client is confused about
     * which video it is saving, and silently keeping one of them would persist that confusion.
     *
     * @param declaredProvider the client's claim, or null to let the server decide alone
     * @throws BusinessException on the same failures as {@link #resolve(String)}, plus a declared
     *                           provider that contradicts the URL
     */
    public ResolvedVideo resolve(String rawUrl, VideoProvider declaredProvider) {
        ResolvedVideo resolved = resolve(rawUrl);
        if (declaredProvider != null && declaredProvider != resolved.provider()) {
            throw new BusinessException("error.video.providerMismatch");
        }
        return resolved;
    }

    /** Whether this URL is one Manara can play, without raising anything when it is not. */
    public boolean isSupported(String rawUrl) {
        return tryResolve(rawUrl).isPresent();
    }

    /** {@link #resolve(String)} without the exception, for callers that have a fallback. */
    public Optional<ResolvedVideo> tryResolve(String rawUrl) {
        try {
            return Optional.of(resolve(rawUrl));
        } catch (BusinessException e) {
            return Optional.empty();
        }
    }

    /**
     * What to tell a client about a video already in the database.
     *
     * <p>The stored URL is re-read rather than trusted to the derived columns, so a row that
     * predates them — or one the back-fill migration has not been run against — describes itself
     * correctly anyway. This is the mechanism that lets the deployment go out before, or entirely
     * without, the migration.
     *
     * <p>Only when the URL no longer parses do the stored columns answer instead, which keeps a
     * lesson whose URL shape Manara has since stopped recognising from losing its player.
     */
    public Optional<ResolvedVideo> describe(VideoSource source) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            return Optional.empty();
        }

        Optional<ResolvedVideo> fromUrl = tryResolve(source.getUrl());
        if (fromUrl.isPresent()) {
            return fromUrl;
        }
        return fromStoredColumns(source);
    }

    /**
     * Last resort: rebuild what we can from what the row remembers. Requires both a provider and an
     * id, because an embed URL cannot be built without them.
     */
    private Optional<ResolvedVideo> fromStoredColumns(VideoSource source) {
        if (source.getProvider() == null || source.getExternalId() == null) {
            return Optional.empty();
        }
        return adapterFor(source.getProvider()).map(adapter -> {
            VideoReference reference = VideoReference.of(source.getExternalId());
            String thumbnail = source.getThumbnailUrl() != null
                    ? source.getThumbnailUrl()
                    : adapter.thumbnailUrl(reference);
            return new ResolvedVideo(
                    adapter.provider(),
                    source.getUrl(),
                    reference,
                    adapter.canonicalUrl(reference),
                    adapter.embedUrl(reference),
                    thumbnail);
        });
    }

    private ResolvedVideo describe(VideoProviderAdapter adapter, String url, VideoReference reference) {
        return new ResolvedVideo(
                adapter.provider(),
                url,
                reference,
                adapter.canonicalUrl(reference),
                adapter.embedUrl(reference),
                adapter.thumbnailUrl(reference));
    }

    /**
     * Only absolute http(s) URLs are videos. A relative path, a {@code javascript:} payload or a
     * {@code file:} path is rejected here rather than being handed to an adapter that would have
     * to guard against it too.
     */
    private Optional<URI> parseUri(String url) {
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            if (scheme == null || uri.getHost() == null) return Optional.empty();

            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) return Optional.empty();

            return Optional.of(uri);
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
