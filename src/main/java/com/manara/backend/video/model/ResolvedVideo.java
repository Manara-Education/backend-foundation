package com.manara.backend.video.model;

/**
 * A video URL after the resolver has understood it: everything a caller needs to store the video,
 * render a player for it, or ask its provider about it, with no further parsing.
 *
 * <p>This is the read model the API responses are built from and the write model a lesson is saved
 * from, so the two can never disagree about what a given URL means.
 *
 * @param provider     the platform hosting it
 * @param url          the address as supplied, unchanged
 * @param reference    the provider's id for the video, plus any unlisted-link token
 * @param canonicalUrl the tidied public address of the same video, for display and comparison
 * @param embedUrl     what an iframe is pointed at; players append their own parameters
 * @param thumbnailUrl still image if the provider exposes one at a predictable address, else null
 */
public record ResolvedVideo(
        VideoProvider provider,
        String url,
        VideoReference reference,
        String canonicalUrl,
        String embedUrl,
        String thumbnailUrl) {

    public String externalId() {
        return reference == null ? null : reference.externalId();
    }

    /** The video source to persist for this URL, carrying the derived columns alongside it. */
    public VideoSource toVideoSource() {
        return VideoSource.builder()
                .provider(provider)
                .url(url)
                .externalId(externalId())
                .thumbnailUrl(thumbnailUrl)
                .build();
    }

    /**
     * The same thing, but not at the cost of a thumbnail that had to be fetched.
     *
     * <p>YouTube's still is derivable from the video id, so {@link #thumbnailUrl()} always has it.
     * Vimeo's is not: it arrives out of band when {@code VideoMetadataService} asks, and it is
     * written straight onto the stored {@link VideoSource}. Replacing that source wholesale on the
     * next save — which is what saving a lesson used to do — threw the fetched still away and
     * scheduled no new lookup to get it back, because the URL had not changed. The lesson then
     * rendered without a poster frame until someone re-pointed it at a different video.
     *
     * <p>So the derived columns are still rewritten from the URL on every save, which is what keeps
     * a pre-provider row up to date; only a still the resolver cannot produce is carried over, and
     * only while the video is genuinely the same one.
     *
     * @param existing the source currently stored on the lesson, or {@code null} for a new one
     */
    public VideoSource toVideoSource(VideoSource existing) {
        VideoSource next = toVideoSource();
        boolean sameVideo = existing != null && java.util.Objects.equals(existing.getUrl(), url);
        if (next.getThumbnailUrl() == null && sameVideo && existing.getThumbnailUrl() != null) {
            next.setThumbnailUrl(existing.getThumbnailUrl());
        }
        return next;
    }
}
