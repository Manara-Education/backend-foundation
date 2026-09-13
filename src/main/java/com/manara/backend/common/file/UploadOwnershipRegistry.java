package com.manara.backend.common.file;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Optional;

/**
 * The one place that says who uploaded a file, and the one place that turns a URL into a file.
 *
 * <p>Both halves are here on purpose, because they are the same mistake seen twice. The finding
 * this closes is that a {@code /uploads/...} string arriving in a request body was treated as
 * proof of something. It is not: it is a claim, and turning it into a fact requires (a) resolving
 * it to a stored name, carefully, and (b) looking that name up in a record somebody actually wrote.
 * Scattering either step would let a future caller do half of it.
 *
 * <h2>Recording</h2>
 * {@link #record} is called at the upload boundary — the point that has both the bytes and the
 * authenticated account. Nothing else may write to this table: an ownership record created anywhere
 * else would be an inference, and an inference here is exactly what the finding is about.
 *
 * <h2>Reading</h2>
 * {@link #ownerOf} returns empty for anything it cannot vouch for — a URL that is not an upload URL,
 * a name that could not be a stored name, and, most commonly, a file uploaded before this record
 * existed. Callers must read empty as "do not act", never as "no objection".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UploadOwnershipRegistry {

    /** The route uploads are served from. Everything else — an absolute CDN URL — is not ours. */
    private static final String UPLOAD_PREFIX = "/uploads/";

    private final UploadedFileRepository uploadedFileRepository;

    /**
     * Records who stored a file, immediately after the bytes land.
     *
     * <p>Intended for the upload boundary: the method that has just written the file and knows the
     * authenticated uploader. It is deliberately a plain user id and two descriptive values rather
     * than a {@code User} and a {@code MultipartFile}, so that whichever of the controller or the
     * service ends up holding the principal can call it without either of them growing a dependency
     * on the other's arguments.
     *
     * <p>Idempotent by stored name. Names are freshly generated UUIDs so a second call for the same
     * file should not happen; if one does — a retry, a replayed request — the existing record wins
     * rather than the table gaining a second, contradictory answer to "who owns this?".
     *
     * @param fileUrl        the URL the store returned, {@code /uploads/<uuid>.<ext>}
     * @param uploaderUserId the authenticated account that uploaded it; never guessed
     * @return the stored record, or empty if the URL is not an upload URL or the uploader is unknown
     */
    @Transactional
    public Optional<UploadedFile> record(String fileUrl, Long uploaderUserId,
                                         String contentType, Long sizeBytes) {
        if (uploaderUserId == null) {
            // No identity, no record. An upload whose uploader is unknown must leave the file
            // owner-unknown — which makes it undeletable — rather than owned by a placeholder.
            log.warn("Upload stored with no uploader identity; it will be owner-unknown: {}", fileUrl);
            return Optional.empty();
        }
        Optional<String> storedName = storedNameOf(fileUrl);
        if (storedName.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(uploadedFileRepository.findByStoredName(storedName.get())
                .orElseGet(() -> uploadedFileRepository.save(UploadedFile.builder()
                        .storedName(storedName.get())
                        .uploaderUserId(uploaderUserId)
                        .contentType(normalizedContentType(contentType))
                        .sizeBytes(sizeBytes != null && sizeBytes >= 0 ? sizeBytes : null)
                        .build())));
    }

    /**
     * Who uploaded the file this URL names, if anybody is on record.
     *
     * <p>Empty means "unknown", and unknown covers three different situations that all deserve the
     * same treatment: the URL is not an upload URL at all, the URL is malformed, or the file is one
     * of the many that predate this record. None of them is a statement that the caller owns it.
     */
    @Transactional(readOnly = true)
    public Optional<UploadedFile> ownerOf(String fileUrl) {
        return storedNameOf(fileUrl).flatMap(uploadedFileRepository::findByStoredName);
    }

    /**
     * Drops a record whose file has been removed.
     *
     * <p>Called only after the bytes are actually gone. A record for a file that no longer exists
     * would have the registry asserting ownership of nothing, and would make any later inventory
     * pass believe the file is still accounted for. A row that has already gone — two replacements
     * racing each other — is not an error, so this is a no-op rather than a failure.
     */
    @Transactional
    public void forget(Long uploadedFileId) {
        if (uploadedFileId == null) {
            return;
        }
        uploadedFileRepository.deleteById(uploadedFileId);
    }

    /**
     * The stored name a {@code /uploads/} URL refers to, if it plausibly refers to one at all.
     *
     * <p>Deliberately strict, and strict in the direction of returning nothing. This value is used
     * to look up an authorisation fact, so a name that is not obviously a single, ordinary file
     * name — anything with a path separator, a traversal segment, a query string, an escape — is
     * refused outright instead of being cleaned up into something acceptable. Sanitising is how a
     * hostile string gets a second chance; refusing is how it gets none.
     *
     * <p>An absolute URL ({@code https://cdn.example.com/cover.png}) is not an upload URL and is
     * refused here, which is what keeps external covers entirely outside this mechanism.
     */
    public static Optional<String> storedNameOf(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith(UPLOAD_PREFIX)) {
            return Optional.empty();
        }
        String name = fileUrl.substring(UPLOAD_PREFIX.length());
        if (name.isBlank() || !name.matches("[A-Za-z0-9._-]{1,255}") || name.contains("..")) {
            return Optional.empty();
        }
        return Optional.of(name);
    }

    private static String normalizedContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String trimmed = contentType.trim().toLowerCase(Locale.ROOT);
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }
}
