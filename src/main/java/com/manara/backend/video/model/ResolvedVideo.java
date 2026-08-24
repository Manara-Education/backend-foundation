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
}
