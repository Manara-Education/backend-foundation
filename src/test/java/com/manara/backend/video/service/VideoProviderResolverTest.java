package com.manara.backend.video.service;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.video.VideoProviderFixtures;
import com.manara.backend.video.model.ResolvedVideo;
import com.manara.backend.video.model.VideoProvider;
import com.manara.backend.video.model.VideoSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The resolver is the single answer to "what is this URL?", so this is where every URL shape
 * Manara claims to accept is actually proven to be accepted, and every shape it claims to refuse is
 * proven to be refused.
 *
 * <p>Both adapters are exercised through the resolver rather than directly. That is deliberate: the
 * resolver is what the rest of the application talks to, so testing through it is what proves the
 * dispatch — the part that decides which adapter gets the URL — and not just the regexes.
 */
class VideoProviderResolverTest {

    private final VideoProviderResolver resolver = VideoProviderFixtures.resolver();

    @Nested
    @DisplayName("YouTube")
    class YouTube {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "https://youtube.com/watch?v=dQw4w9WgXcQ,           dQw4w9WgXcQ",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ,       dQw4w9WgXcQ",
                "http://www.youtube.com/watch?v=dQw4w9WgXcQ,        dQw4w9WgXcQ",
                "https://m.youtube.com/watch?v=dQw4w9WgXcQ,         dQw4w9WgXcQ",
                "https://youtu.be/dQw4w9WgXcQ,                      dQw4w9WgXcQ",
                "https://youtube.com/embed/dQw4w9WgXcQ,             dQw4w9WgXcQ",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ,        dQw4w9WgXcQ",
                "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ,dQw4w9WgXcQ",
                "https://www.youtube.com/live/dQw4w9WgXcQ,          dQw4w9WgXcQ",
                "https://www.youtube.com/v/dQw4w9WgXcQ,             dQw4w9WgXcQ",
        })
        void readsTheVideoIdOutOfEveryShapeYouTubeHandsOut(String url, String expectedId) {
            ResolvedVideo video = resolver.resolve(url);

            assertThat(video.provider()).isEqualTo(VideoProvider.YOUTUBE);
            assertThat(video.externalId()).isEqualTo(expectedId);
        }

        /**
         * A watch URL arrives carrying a playlist, a start offset, a share campaign. None of it
         * identifies the video, so none of it survives into the embed — but the URL the instructor
         * typed is kept exactly as typed.
         */
        @Test
        void keepsTheOriginalUrlAndStripsIncidentalParametersFromTheEmbed() {
            String pasted = "https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=PL123&t=42s&si=xyz";

            ResolvedVideo video = resolver.resolve(pasted);

            assertThat(video.url()).isEqualTo(pasted);
            assertThat(video.embedUrl()).isEqualTo("https://www.youtube.com/embed/dQw4w9WgXcQ");
            assertThat(video.canonicalUrl()).isEqualTo("https://www.youtube.com/watch?v=dQw4w9WgXcQ");
        }

        @Test
        void derivesTheThumbnailFromTheIdWithoutAskingYouTube() {
            ResolvedVideo video = resolver.resolve("https://youtu.be/dQw4w9WgXcQ");

            assertThat(video.thumbnailUrl())
                    .isEqualTo("https://img.youtube.com/vi/dQw4w9WgXcQ/hqdefault.jpg");
        }

        @Test
        void trimsSurroundingWhitespaceBeforeReadingTheUrl() {
            ResolvedVideo video = resolver.resolve("  https://youtu.be/dQw4w9WgXcQ  ");

            assertThat(video.provider()).isEqualTo(VideoProvider.YOUTUBE);
            assertThat(video.url()).isEqualTo("https://youtu.be/dQw4w9WgXcQ");
        }

        /** Eleven characters is the id; anything else on a YouTube host is not one. */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://youtube.com/watch?v=abc",
                "https://youtu.be/tooshort",
                "https://www.youtube.com/",
                "https://www.youtube.com/results?search_query=nahw",
        })
        void refusesAYouTubeUrlThatNamesNoVideo(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.videoIdInvalid");
        }
    }

    @Nested
    @DisplayName("Vimeo")
    class Vimeo {

        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "https://vimeo.com/76979871,                        76979871",
                "https://www.vimeo.com/76979871,                    76979871",
                "http://vimeo.com/76979871,                         76979871",
                "https://vimeo.com/76979871/,                       76979871",
                "https://player.vimeo.com/video/76979871,           76979871",
                "https://vimeo.com/channels/staffpicks/76979871,    76979871",
                "https://vimeo.com/groups/motion/videos/76979871,   76979871",
                "https://vimeo.com/album/2222222/video/76979871,    76979871",
                "https://vimeo.com/showcase/2222222/video/76979871, 76979871",
        })
        void readsTheVideoIdOutOfEveryShapeVimeoHandsOut(String url, String expectedId) {
            ResolvedVideo video = resolver.resolve(url);

            assertThat(video.provider()).isEqualTo(VideoProvider.VIMEO);
            assertThat(video.externalId()).isEqualTo(expectedId);
        }

        /**
         * The reason the id alone is not enough. An unlisted Vimeo video only plays for a viewer who
         * presents the token from its link, so the token has to survive being pasted into Manara and
         * come back out in the embed URL. Dropping it produces a link that looks right and plays
         * nothing.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://vimeo.com/76979871/abc123def4",
                "https://vimeo.com/76979871?h=abc123def4",
                "https://player.vimeo.com/video/76979871?h=abc123def4",
        })
        void keepsTheUnlistedLinkTokenAndPutsItBackIntoTheEmbed(String url) {
            ResolvedVideo video = resolver.resolve(url);

            assertThat(video.provider()).isEqualTo(VideoProvider.VIMEO);
            assertThat(video.externalId()).isEqualTo("76979871");
            assertThat(video.reference().privacyHash()).isEqualTo("abc123def4");
            assertThat(video.embedUrl()).isEqualTo("https://player.vimeo.com/video/76979871?h=abc123def4");
            assertThat(video.canonicalUrl()).isEqualTo("https://vimeo.com/76979871/abc123def4");
        }

        @Test
        void buildsAPlainEmbedForAPublicVideo() {
            ResolvedVideo video = resolver.resolve("https://vimeo.com/76979871");

            assertThat(video.embedUrl()).isEqualTo("https://player.vimeo.com/video/76979871");
            assertThat(video.canonicalUrl()).isEqualTo("https://vimeo.com/76979871");
        }

        /**
         * Vimeo stills are addressed by content hash on a CDN, so there is nothing to derive. The
         * absence is expected and is filled in later by the metadata refresh.
         */
        @Test
        void hasNoThumbnailUntilVimeoIsAsked() {
            assertThat(resolver.resolve("https://vimeo.com/76979871").thumbnailUrl()).isNull();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://vimeo.com/",
                "https://vimeo.com/channels/staffpicks",
                "https://vimeo.com/notanumber",
        })
        void refusesAVimeoUrlThatNamesNoVideo(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.videoIdInvalid");
        }
    }

    @Nested
    @DisplayName("Refusals")
    class Refusals {

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"", "   "})
        void refusesABlankUrl(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.urlRequired");
        }

        @Test
        void refusesANullUrl() {
            assertThatThrownBy(() -> resolver.resolve(null))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.urlRequired");
        }

        /**
         * Anything that is not an absolute http(s) address is turned away before an adapter sees it,
         * which is also what keeps a {@code javascript:} payload from ever reaching a page that
         * might put it in an iframe.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "not a url at all",
                "youtube.com/watch?v=dQw4w9WgXcQ",
                "/watch?v=dQw4w9WgXcQ",
                "javascript:alert(1)",
                "file:///etc/passwd",
                "ftp://youtube.com/watch?v=dQw4w9WgXcQ",
        })
        void refusesAnythingThatIsNotAnAbsoluteWebAddress(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.urlMalformed");
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://dailymotion.com/video/x8abcd",
                "https://wistia.com/medias/abc123",
                "https://example.com/lesson.mp4",
        })
        void refusesAPlatformNoAdapterClaims(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.providerUnsupported");
        }

        /**
         * Host matching is exact. A domain that merely contains a provider's name belongs to
         * whoever registered it, not to the provider.
         */
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
                "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
                "https://vimeo.com.evil.example/76979871",
        })
        void refusesALookalikeDomain(String url) {
            assertThatThrownBy(() -> resolver.resolve(url))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.providerUnsupported");
        }
    }

    @Nested
    @DisplayName("A provider claimed by the client")
    class DeclaredProvider {

        @Test
        void isAcceptedWhenItAgreesWithTheUrl() {
            ResolvedVideo video = resolver.resolve("https://vimeo.com/76979871", VideoProvider.VIMEO);

            assertThat(video.provider()).isEqualTo(VideoProvider.VIMEO);
        }

        /**
         * The contradiction that must never become a stored row: a provider column describing a
         * different platform from the URL beside it.
         */
        @Test
        void isRejectedWhenItContradictsTheUrl() {
            assertThatThrownBy(() ->
                    resolver.resolve("https://vimeo.com/76979871", VideoProvider.YOUTUBE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("error.video.providerMismatch");
        }

        @Test
        void isOptional() {
            assertThat(resolver.resolve("https://youtu.be/dQw4w9WgXcQ", null).provider())
                    .isEqualTo(VideoProvider.YOUTUBE);
        }
    }

    @Nested
    @DisplayName("Describing a stored video")
    class Describing {

        /**
         * The backward-compatibility guarantee in one test. This is a row exactly as the prototype
         * left it: a URL and nothing else, no provider, no id, no thumbnail. It has to describe
         * itself completely, because that is what makes the deployment safe without the back-fill
         * migration having been run.
         */
        @Test
        void worksForALegacyRowThatOnlyHasAUrl() {
            VideoSource legacy = VideoSource.ofUrl("https://www.youtube.com/watch?v=Jc__iOQgQNM");

            ResolvedVideo video = resolver.describe(legacy).orElseThrow();

            assertThat(video.provider()).isEqualTo(VideoProvider.YOUTUBE);
            assertThat(video.externalId()).isEqualTo("Jc__iOQgQNM");
            assertThat(video.embedUrl()).isEqualTo("https://www.youtube.com/embed/Jc__iOQgQNM");
            assertThat(video.thumbnailUrl())
                    .isEqualTo("https://img.youtube.com/vi/Jc__iOQgQNM/hqdefault.jpg");
        }

        /**
         * A stored thumbnail wins over a derived one only when the URL cannot be read; while the URL
         * parses, the derived answer is authoritative and cannot go stale.
         */
        @Test
        void fallsBackToTheStoredColumnsWhenTheUrlNoLongerParses() {
            VideoSource stored = VideoSource.builder()
                    .provider(VideoProvider.VIMEO)
                    .url("https://vimeo.com/some-shape-we-stopped-recognising")
                    .externalId("76979871")
                    .thumbnailUrl("https://i.vimeocdn.com/video/stored.jpg")
                    .build();

            ResolvedVideo video = resolver.describe(stored).orElseThrow();

            assertThat(video.provider()).isEqualTo(VideoProvider.VIMEO);
            assertThat(video.externalId()).isEqualTo("76979871");
            assertThat(video.embedUrl()).isEqualTo("https://player.vimeo.com/video/76979871");
            assertThat(video.thumbnailUrl()).isEqualTo("https://i.vimeocdn.com/video/stored.jpg");
        }

        /** Unreadable and unclassified: the lesson keeps its URL, and simply has no player. */
        @Test
        void isEmptyWhenNeitherTheUrlNorTheColumnsCanBeRead() {
            VideoSource unusable = VideoSource.ofUrl("https://example.com/whatever");

            assertThat(resolver.describe(unusable)).isEmpty();
        }

        @Test
        void isEmptyForNoVideoAtAll() {
            assertThat(resolver.describe(null)).isEmpty();
            assertThat(resolver.describe(VideoSource.ofUrl(null))).isEmpty();
            assertThat(resolver.describe(VideoSource.ofUrl("  "))).isEmpty();
        }
    }

    @Test
    void reportsWhetherAUrlIsPlayableWithoutRaising() {
        assertThat(resolver.isSupported("https://youtu.be/dQw4w9WgXcQ")).isTrue();
        assertThat(resolver.isSupported("https://vimeo.com/76979871")).isTrue();
        assertThat(resolver.isSupported("https://example.com/video.mp4")).isFalse();
        assertThat(resolver.isSupported("nonsense")).isFalse();
    }
}
