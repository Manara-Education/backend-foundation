package com.manara.backend.video.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

/**
 * The video a lesson plays, independent of who hosts it.
 *
 * <p>Embedded rather than a table of its own: a lesson has exactly one video and never shares it,
 * so a join would buy nothing. {@code url} maps to the {@code lessons.video_url} column the
 * prototype already wrote, which is what lets every existing row keep its video without being
 * rewritten.
 *
 * <h2>Which fields are authoritative</h2>
 *
 * <p>{@code url} is. It holds the address the instructor pasted, trimmed and otherwise untouched —
 * so the course editor shows back exactly what was typed, and a URL form Manara has not learned to
 * parse yet is still preserved rather than discarded.
 *
 * <p>{@code provider}, {@code externalId} and {@code thumbnailUrl} are derived from it and are
 * therefore a cache: they make the provider queryable in SQL and spare the read path a parse, but
 * {@link com.manara.backend.video.service.VideoProviderResolver} re-derives them from the URL on
 * read and only falls back to these columns when the URL no longer parses. That ordering is what
 * makes the back-fill migration optional rather than a precondition for the deployment.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class VideoSource {

    /**
     * Null for a row written before providers existed and never saved since, and for a URL no
     * adapter recognises. Readers treat null as "ask the resolver", never as "broken".
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "video_provider", length = 32)
    private VideoProvider provider;

    /** The address as the instructor gave it. Never rewritten to a canonical form. */
    @Column(name = "video_url", nullable = false, columnDefinition = "TEXT")
    private String url;

    /** The provider's own identifier — a YouTube video id, a Vimeo numeric id. */
    @Column(name = "external_video_id", length = 128)
    private String externalId;

    /**
     * Still image for the video. YouTube's is a deterministic URL known the moment the id is
     * parsed; Vimeo's has to be fetched, so it lands here when the metadata refresh completes and
     * is null until then.
     */
    @Column(name = "video_thumbnail_url", columnDefinition = "TEXT")
    private String thumbnailUrl;

    public static VideoSource ofUrl(String url) {
        return VideoSource.builder().url(url).build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VideoSource other)) return false;
        return provider == other.provider
                && Objects.equals(url, other.url)
                && Objects.equals(externalId, other.externalId)
                && Objects.equals(thumbnailUrl, other.thumbnailUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, url, externalId, thumbnailUrl);
    }
}
