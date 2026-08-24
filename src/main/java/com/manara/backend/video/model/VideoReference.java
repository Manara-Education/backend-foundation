package com.manara.backend.video.model;

/**
 * What an adapter reads out of a URL: which video, and — where the provider has the concept — the
 * unlisted-link secret needed to play it.
 *
 * @param externalId  the provider's identifier for the video
 * @param privacyHash Vimeo's unlisted-link token, carried in the URL as a second path segment or
 *                    an {@code h=} parameter. Null for every public video and for every provider
 *                    that has no such concept. It is part of the address, not a credential: it is
 *                    stored inside the pasted URL either way, and dropping it would make an
 *                    unlisted video unplayable while looking like a working link.
 */
public record VideoReference(String externalId, String privacyHash) {

    public static VideoReference of(String externalId) {
        return new VideoReference(externalId, null);
    }

    public boolean hasPrivacyHash() {
        return privacyHash != null && !privacyHash.isBlank();
    }
}
