package com.manara.backend.video.model;

/**
 * What a provider could tell us about a video, out of band from the request that saved it.
 *
 * <p>Either field may be null: providers differ in what they expose without an API key, and a
 * fetch that fails is reported as {@link #empty()} rather than as an error, because a lesson whose
 * duration is not known yet is still a working lesson.
 *
 * @param durationSeconds running time, or null when the provider did not give one
 * @param thumbnailUrl    still image, or null when the provider did not give one
 */
public record VideoMetadata(Integer durationSeconds, String thumbnailUrl) {

    private static final VideoMetadata EMPTY = new VideoMetadata(null, null);

    public static VideoMetadata empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return durationSeconds == null && thumbnailUrl == null;
    }

    public boolean hasDuration() {
        return durationSeconds != null && durationSeconds > 0;
    }

    public boolean hasThumbnail() {
        return thumbnailUrl != null && !thumbnailUrl.isBlank();
    }
}
