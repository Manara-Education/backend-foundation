package com.manara.backend.common.file;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

import java.util.List;

/**
 * Where uploaded files live, and what is allowed to become one.
 *
 * <p>The directory used to be hardcoded as {@code Paths.get("uploads")} in two places —
 * {@link FileUploadService} and {@code StaticResourceConfig} — which meant the write path and
 * the read path were only the same by coincidence, and neither could be pointed at a mounted
 * volume without editing code. Both now resolve this one value.
 *
 * <p>The limits below are sized for the production container: 1 GiB, of which the JVM takes 70%
 * as heap. The largest image they permit — 4096 × 4096 at 16 bits a channel with alpha, plus the
 * working copy made to turn or convert it — needs 192 MiB, exactly the decode budget, so it can
 * always be decoded on its own and several ordinary photos can be decoded at once. The previous
 * 50-megapixel limit allowed 200 MB for a single decoded raster, before any copy, with no bound
 * on how many uploads decoded in parallel.
 *
 * @param dir                directory uploads are written to and served from. Relative paths
 *                           resolve against the working directory, which keeps the local
 *                           default working; production sets an absolute path onto a volume.
 * @param allowedMediaTypes  content types accepted at upload. Deliberately a small allow-list:
 *                           everything this application uploads is an image (course covers and
 *                           instructor banners), so nothing else has any reason to be accepted.
 * @param allowedExtensions  filename extensions accepted, checked alongside the content type.
 * @param maxPixels          maximum width × height. A 5 MB file can decode to hundreds of
 *                           megabytes of pixels, so the size limit alone does not bound memory.
 *                           4096 × 4096 fits a 12-megapixel phone photo with room to spare.
 * @param maxDimension       maximum width or height on its own; no cover or banner is wider.
 * @param maxFileSize        maximum upload size. The multipart limit refuses a larger request
 *                           first; this states the same bound where the bytes are read into memory.
 * @param decodeMemoryBudget memory every decode in flight may hold between them. An upload
 *                           reserves its estimated share before decoding and waits for room.
 */
@ConfigurationProperties(prefix = "app.uploads")
public record UploadProperties(
        String dir,
        List<String> allowedMediaTypes,
        List<String> allowedExtensions,
        long maxPixels,
        int maxDimension,
        DataSize maxFileSize,
        DataSize decodeMemoryBudget) {

    public UploadProperties {
        dir = (dir == null || dir.isBlank()) ? "uploads" : dir;
        allowedMediaTypes = allowedMediaTypes == null || allowedMediaTypes.isEmpty()
                ? List.of("image/jpeg", "image/png", "image/webp", "image/gif")
                : allowedMediaTypes.stream().map(t -> t.trim().toLowerCase()).toList();
        allowedExtensions = allowedExtensions == null || allowedExtensions.isEmpty()
                ? List.of("jpg", "jpeg", "png", "webp", "gif")
                : allowedExtensions.stream().map(e -> e.trim().toLowerCase()).toList();
        maxPixels = maxPixels <= 0 ? 4096L * 4096L : maxPixels;
        maxDimension = maxDimension <= 0 ? 8192 : maxDimension;
        maxFileSize = maxFileSize == null || maxFileSize.toBytes() <= 0 ? DataSize.ofMegabytes(5) : maxFileSize;
        decodeMemoryBudget = decodeMemoryBudget == null || decodeMemoryBudget.toBytes() <= 0
                ? DataSize.ofMegabytes(192)
                : decodeMemoryBudget;
    }
}
