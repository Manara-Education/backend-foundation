package com.manara.backend.email.model;

/**
 * An image embedded in the message body and referenced from the HTML as {@code src="cid:<id>"}.
 *
 * <p>Embedding rather than hot-linking is deliberate for transactional mail: most clients block
 * remote images until the reader opts in, which would leave the brand mark as a broken box on first
 * open. It also means no public asset host is required.
 *
 * @param contentId  matches the {@code cid:} reference in the HTML
 * @param fileName   name shown if a client surfaces the part
 * @param contentType MIME type, e.g. {@code image/png}
 * @param base64Content the image bytes, Base64-encoded
 */
public record InlineImage(
        String contentId,
        String fileName,
        String contentType,
        String base64Content
) {
}
