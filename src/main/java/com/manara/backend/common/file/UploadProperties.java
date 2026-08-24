package com.manara.backend.common.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Where uploaded files live, and what is allowed to become one.
 *
 * <p>The directory used to be hardcoded as {@code Paths.get("uploads")} in two places —
 * {@link FileUploadService} and {@code StaticResourceConfig} — which meant the write path and
 * the read path were only the same by coincidence, and neither could be pointed at a mounted
 * volume without editing code. Both now resolve this one value.
 *
 * @param dir               directory uploads are written to and served from. Relative paths
 *                          resolve against the working directory, which keeps the local
 *                          default working; production sets an absolute path onto a volume.
 * @param allowedMediaTypes content types accepted at upload. Deliberately a small allow-list:
 *                          everything this application uploads is an image (course covers and
 *                          instructor banners), so nothing else has any reason to be accepted.
 * @param allowedExtensions filename extensions accepted, checked alongside the content type.
 * @param maxPixels         maximum width × height. A 5 MB file can decode to hundreds of
 *                          megabytes of pixels, so the size limit alone does not bound memory.
 */
@ConfigurationProperties(prefix = "app.uploads")
public record UploadProperties(
        String dir,
        List<String> allowedMediaTypes,
        List<String> allowedExtensions,
        long maxPixels) {

    public UploadProperties {
        dir = (dir == null || dir.isBlank()) ? "uploads" : dir;
        allowedMediaTypes = allowedMediaTypes == null || allowedMediaTypes.isEmpty()
                ? List.of("image/jpeg", "image/png", "image/webp", "image/gif")
                : allowedMediaTypes.stream().map(t -> t.trim().toLowerCase()).toList();
        allowedExtensions = allowedExtensions == null || allowedExtensions.isEmpty()
                ? List.of("jpg", "jpeg", "png", "webp", "gif")
                : allowedExtensions.stream().map(e -> e.trim().toLowerCase()).toList();
        maxPixels = maxPixels <= 0 ? 50_000_000L : maxPixels;
    }
}
