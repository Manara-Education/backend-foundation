-- =============================================================================
-- V15 — Who uploaded a file, so that deleting one can be a decision about
--       ownership rather than about a string in a payload
-- =============================================================================
--
-- Nothing on this platform has ever recorded who uploaded a file. `storeFile`
-- writes `<uuid>.<ext>` into a shared directory, returns `/uploads/<uuid>.<ext>`
-- and forgets; the only thing that ever remembers the file afterwards is
-- whichever course or banner happens to hold that string in a column. So the
-- one and only fact available at deletion time was the URL itself — and a URL
-- carried in a request body is a claim, not a credential. This table is the
-- missing fact.
--
-- WHAT A ROW MEANS
--
--   "The account in uploader_user_id put these bytes on this disk, under this
--   stored name." Nothing more. It is not a reference count, not an access
--   rule, and not a statement about who may *use* the file — public reads of
--   /uploads/** are unchanged. It exists to answer one question, asked in one
--   place: may this particular caller cause this particular file to be removed?
--
-- WHY THERE IS NO BACK-FILL, AND WHY THAT IS THE SAFE CHOICE
--
--   Every file already on disk predates this table, and there is no honest way
--   to say who uploaded it. A course row holding /uploads/x.png proves that a
--   course points at x.png; it does not prove that this course's instructor
--   uploaded x.png — that inference is precisely the mistake the finding is
--   about, and writing it into the schema would launder a guess into a stored
--   fact and then let it authorise a delete.
--
--   So this migration writes no rows at all. No INSERT, no UPDATE, no DELETE
--   appears below, and no file on disk is touched. Every pre-existing upload is
--   therefore "owner unknown", which the retention rule reads as *never
--   deletable*: an absent row is a refusal to act, not a licence. A legacy
--   cover keeps working exactly as it does today — it is served, replaced and
--   referenced as before — and the only thing it loses is the ability to be
--   automatically destroyed, which is the ability that was doing the harm.
--
--   The consequence is orphaned bytes: files no course or banner references any
--   more will simply stay on the disk. That is deliberate and is the cheaper
--   error by a wide margin. An orphan costs storage; a wrong delete costs
--   somebody else's course cover, permanently. Reclaiming them needs an
--   inventory pass that reconciles the directory against the references, which
--   is operations work with a human in the loop, not something a migration or a
--   request handler should improvise.
--
-- DEPLOYMENT COMPATIBILITY
--
--   Additive only. One new table, one new index on an existing column, no
--   column dropped, retyped or made stricter, and no existing constraint
--   altered. An instance running the previous build against this schema never
--   mentions the table and behaves exactly as it did. An instance running the
--   new build against the old schema would fail Hibernate's validation on a
--   missing table, so this migration goes first — which is the order Flyway
--   already guarantees by running before the context refreshes.
--
--   Rolling back is dropping the table. Nothing else reads it, no existing
--   column references it, and the retention rule fails closed without it: with
--   no ownership fact available, no automatic deletion is authorised. Rolling
--   back therefore loses future cleanup, never data.
-- =============================================================================


-- 1. The ownership record. ---------------------------------------------------
--
-- stored_name, not the URL. The URL is presentation — "/uploads/" is a route,
-- and a route can be re-mounted — whereas the stored name is the file. Storing
-- the name means the lookup is over the identity of the bytes, and the UNIQUE
-- constraint then says the thing that has to be true: one file, one owner. Two
-- rows claiming the same file is the state in which "is this yours?" has two
-- answers, and the database refuses it rather than leaving the service to pick.
--
-- uploader_user_id is NOT NULL and a real foreign key. A nullable owner would
-- reintroduce exactly the ambiguity this table exists to remove: the difference
-- between "owned by nobody" and "we never knew" would stop being visible, and
-- one of the two would eventually be read as permission. Absence of a row is
-- the only way to say "unknown", and it is unambiguous.
--
-- The user reference is keyed on users(id) rather than on instructors(id): the
-- account is what uploads, and an instructor profile can be created, renamed or
-- reshaped without changing who put the bytes there. No ON DELETE clause, so a
-- user row with uploads cannot be silently removed out from under them; the
-- platform has no account-deletion flow today, and when it grows one, what
-- happens to that account's files is a product decision to make deliberately
-- rather than a cascade to inherit by accident.

CREATE TABLE IF NOT EXISTS public.uploads
(
    id               bigserial PRIMARY KEY,
    stored_name      varchar(255)                   NOT NULL,
    uploader_user_id bigint                         NOT NULL,
    content_type     varchar(100),
    size_bytes       bigint,
    created_at       timestamp(6) without time zone NOT NULL DEFAULT now(),

    CONSTRAINT uk_uploads_stored_name UNIQUE (stored_name),
    CONSTRAINT fk_uploads_uploader
        FOREIGN KEY (uploader_user_id) REFERENCES public.users (id),
    CONSTRAINT ck_uploads_size_non_negative
        CHECK (size_bytes IS NULL OR size_bytes >= 0)
);


-- 2. The two access paths. ---------------------------------------------------
--
-- By stored name is the ownership question, and it is already served by the
-- unique constraint's index. By uploader is the other question the record makes
-- answerable — "what has this account put on the disk?" — which is what a quota
-- or an inventory pass reads.

CREATE INDEX IF NOT EXISTS idx_uploads_uploader
    ON public.uploads USING btree (uploader_user_id);


-- 3. Finding the references to a file. ---------------------------------------
--
-- Ownership alone does not make a file deletable: a file somebody else's course
-- still shows must survive its uploader replacing their own cover, or "clean up
-- my old cover" becomes "break their page". The remaining-reference count is
-- read on the deletion path, once per cover replacement, so it gets an index.
--
-- courses.image only. banners.image_url is varchar(1024) and a btree entry is
-- bounded at about 2700 bytes, so a long URL could make an index on it reject
-- the banner that carries it — trading a rare cleanup query's speed for a
-- product write that fails. The banners table is small and its scan is cheap;
-- this is not a trade worth making.

CREATE INDEX IF NOT EXISTS idx_courses_image
    ON public.courses USING btree (image)
    WHERE image IS NOT NULL;


-- 4. What the table says, recorded where the schema is read. ------------------

COMMENT ON TABLE public.uploads IS
    'One row per file stored through the upload endpoint, recording who uploaded it. '
    'Deliberately empty for everything uploaded before this migration: no owner was ever '
    'recorded, and inferring one from the course or banner that happens to reference a URL '
    'is the exact mistake this table exists to stop. A file with no row here is owner '
    'unknown and is never deleted automatically.';

COMMENT ON COLUMN public.uploads.stored_name IS
    'The generated ''<uuid>.<ext>'' the bytes live under, not the served URL. Unique: one '
    'file has one owner, and the database refuses any state in which that has two answers.';

COMMENT ON COLUMN public.uploads.uploader_user_id IS
    'The account that stored the bytes. NOT NULL on purpose - "unknown owner" is expressed '
    'by the absence of a row, never by a null here, so it can never be mistaken for '
    'permission. Never guessed from a reference to the file.';

COMMENT ON COLUMN public.uploads.content_type IS
    'The media type accepted at upload, kept for inventory and support questions. Not a '
    'security control: what the bytes are was already decided by the upload validation.';
