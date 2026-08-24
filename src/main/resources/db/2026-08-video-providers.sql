-- =============================================================================
-- Video providers: lessons that know which platform hosts their video
-- =============================================================================
--
-- This project has no migration tool: the schema is maintained by Hibernate
-- (`spring.jpa.hibernate.ddl-auto=update`). This script exists so the change can
-- be applied deliberately, ahead of the deployment, on environments where that
-- is preferred to letting the application do it on first start.
--
-- RUN THIS BEFORE DEPLOYING THE NEW BUILD, against each environment:
--
--     psql "$DATABASE_URL" -f 2026-08-video-providers.sql
--
-- Afterwards Hibernate's own update finds the columns in place and does nothing.
--
-- The script is idempotent and safe to re-run: every statement is guarded, and
-- the back-fills only ever touch rows whose provider is still NULL.
--
--
-- IS THIS SCRIPT REQUIRED?
--
-- No, and that is deliberate. `VideoProviderResolver#describe` re-reads the
-- provider from `video_url` on every read, so a lesson whose new columns are
-- empty still reports its provider, embed URL and thumbnail correctly, and still
-- plays. Running this script is what makes the provider *queryable in SQL* and
-- saves the read path a parse; it is not what makes existing lessons work.
--
-- The application will therefore behave identically whether this runs before the
-- deploy, after it, or never. It is written to be run.
-- =============================================================================


-- --------------------------------------------------------------------------
-- 1. The three new columns on lessons
-- --------------------------------------------------------------------------
-- All three are nullable, and none of them replaces anything. `video_url` --
-- the column the prototype has always written -- is untouched and remains the
-- authoritative record of the video: it is what the instructor typed, it is what
-- the course editor shows back, and it is what the three columns below are
-- derived from.
--
-- Nothing is dropped and nothing is renamed by this script. A rollback to the
-- previous build needs no counter-migration: the old code simply ignores these
-- columns.

ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS video_provider VARCHAR(32);

ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS external_video_id VARCHAR(128);

ALTER TABLE lessons
    ADD COLUMN IF NOT EXISTS video_thumbnail_url TEXT;


-- --------------------------------------------------------------------------
-- 2. Back-fill: YouTube
-- --------------------------------------------------------------------------
-- Every lesson in the database today is a YouTube lesson -- it was the only
-- thing the product accepted -- so this is the statement that carries the
-- existing catalogue forward.
--
-- The host is matched anchored and exactly, with only the `www.` and `m.`
-- prefixes allowed, so a URL that merely *contains* the word youtube cannot be
-- mislabelled. The id patterns are the same set the YouTubeVideoProviderAdapter
-- accepts, in the same order.
--
-- `WHERE video_provider IS NULL` makes the statement re-runnable and, just as
-- importantly, makes it non-destructive: a row that has already been classified
-- -- by a previous run, or by the application saving the lesson -- is left alone.

UPDATE lessons
SET video_provider    = 'YOUTUBE',
    external_video_id = COALESCE(
            substring(video_url from '[?&]v=([A-Za-z0-9_-]{11})'),
            substring(video_url from 'youtu\.be/([A-Za-z0-9_-]{11})'),
            substring(video_url from '/embed/([A-Za-z0-9_-]{11})'),
            substring(video_url from '/shorts/([A-Za-z0-9_-]{11})'),
            substring(video_url from '/live/([A-Za-z0-9_-]{11})'),
            substring(video_url from '/v/([A-Za-z0-9_-]{11})')
        )
WHERE video_provider IS NULL
  AND video_url ~* '^https?://(www\.|m\.)?(youtube\.com|youtu\.be|youtube-nocookie\.com)/'
  AND COALESCE(
          substring(video_url from '[?&]v=([A-Za-z0-9_-]{11})'),
          substring(video_url from 'youtu\.be/([A-Za-z0-9_-]{11})'),
          substring(video_url from '/embed/([A-Za-z0-9_-]{11})'),
          substring(video_url from '/shorts/([A-Za-z0-9_-]{11})'),
          substring(video_url from '/live/([A-Za-z0-9_-]{11})'),
          substring(video_url from '/v/([A-Za-z0-9_-]{11})')
      ) IS NOT NULL;


-- --------------------------------------------------------------------------
-- 3. Back-fill: Vimeo
-- --------------------------------------------------------------------------
-- Expected to match nothing on a database that predates Vimeo support. It is
-- here so that this script is also the correct thing to run against an
-- environment where the new build has already been live for a while.
--
-- The id is looked for under each shape Vimeo publishes -- bare, player, channel,
-- group, album -- rather than assumed to be the first path segment.

UPDATE lessons
SET video_provider    = 'VIMEO',
    external_video_id = COALESCE(
            substring(video_url from 'vimeo\.com/video/(\d+)'),
            substring(video_url from 'vimeo\.com/channels/[^/]+/(\d+)'),
            substring(video_url from 'vimeo\.com/groups/[^/]+/videos/(\d+)'),
            substring(video_url from 'vimeo\.com/(?:album|showcase)/[^/]+/video/(\d+)'),
            substring(video_url from 'vimeo\.com/(\d+)')
        )
WHERE video_provider IS NULL
  AND video_url ~* '^https?://(www\.)?(vimeo\.com|player\.vimeo\.com)/'
  AND COALESCE(
          substring(video_url from 'vimeo\.com/video/(\d+)'),
          substring(video_url from 'vimeo\.com/channels/[^/]+/(\d+)'),
          substring(video_url from 'vimeo\.com/groups/[^/]+/videos/(\d+)'),
          substring(video_url from 'vimeo\.com/(?:album|showcase)/[^/]+/video/(\d+)'),
          substring(video_url from 'vimeo\.com/(\d+)')
      ) IS NOT NULL;


-- --------------------------------------------------------------------------
-- 4. Back-fill: YouTube thumbnails
-- --------------------------------------------------------------------------
-- YouTube serves a still at an address derivable from the video id, so it can be
-- filled in here without asking YouTube anything. This is the same URL the
-- prototype's instructor cards and preview built client-side; storing it means
-- every surface now reads one field instead of each rebuilding the string.
--
-- Vimeo is deliberately absent: its thumbnails live on a CDN under a content
-- hash, so there is no address to derive. Those are fetched from Vimeo's oEmbed
-- endpoint by VideoMetadataService when a lesson is saved, and stay NULL until
-- then -- which players handle, because a missing thumbnail is a placeholder,
-- not an error.

UPDATE lessons
SET video_thumbnail_url = 'https://img.youtube.com/vi/' || external_video_id || '/hqdefault.jpg'
WHERE video_provider = 'YOUTUBE'
  AND external_video_id IS NOT NULL
  AND video_thumbnail_url IS NULL;


-- --------------------------------------------------------------------------
-- 5. Index
-- --------------------------------------------------------------------------
-- Not for the application, which always reaches a lesson through its course.
-- This is for the operational question this migration makes askable for the
-- first time -- "how much of the catalogue is on which platform?" -- and for the
-- audit query below.

CREATE INDEX IF NOT EXISTS idx_lessons_video_provider ON lessons (video_provider);


-- --------------------------------------------------------------------------
-- 6. Afterwards: what did not classify
-- --------------------------------------------------------------------------
-- Nothing above deletes, blanks or rewrites a video URL, so a row that matched
-- neither platform still has its `video_url` and still renders -- it simply has
-- no provider, and the player shows its unavailable state instead of an empty
-- frame.
--
-- Such rows are possible because the prototype accepted any non-blank string as
-- a video URL. The new build does not, so they can only be pre-existing. They are
-- worth looking at, because an instructor editing one will now be asked to
-- correct the link before the lesson can be saved again:
--
--     SELECT l.id, l.title, c.title AS course, l.video_url
--     FROM lessons l
--     JOIN courses c ON c.id = l.course_id
--     WHERE l.video_provider IS NULL
--     ORDER BY c.title, l.order_index;
--
-- And the count by platform, to confirm the back-fill did what was expected:
--
--     SELECT COALESCE(video_provider, '(unclassified)') AS provider, COUNT(*)
--     FROM lessons
--     GROUP BY 1
--     ORDER BY 2 DESC;
