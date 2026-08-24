package com.manara.backend.video.model;

/**
 * A platform Manara can play a lesson video from.
 *
 * <p>This is the only place the product names a video host. Everything above it — courses,
 * lessons, progress, duration — deals in {@link VideoSource}, and everything below it is a
 * {@link com.manara.backend.video.provider.VideoProviderAdapter} that knows one platform's URL
 * shapes and metadata endpoint.
 *
 * <p>Adding a provider is adding a constant here and an adapter beside the existing two. No
 * course or lesson code needs to learn the name.
 *
 * <p>The constant names are persisted (the {@code lessons.video_provider} column stores them as
 * strings) and are returned verbatim by the API, so they are part of the contract: rename one and
 * both stored rows and deployed clients stop agreeing with the server.
 */
public enum VideoProvider {
    YOUTUBE,
    VIMEO
}
