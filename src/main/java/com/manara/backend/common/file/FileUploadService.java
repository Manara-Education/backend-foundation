package com.manara.backend.common.file;

import com.manara.backend.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class FileUploadService {

    private final UploadProperties properties;
    private final Path fileStorageLocation;

    public FileUploadService(UploadProperties properties) {
        this.properties = properties;
        this.fileStorageLocation = Paths.get(properties.dir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new BusinessException("error.file.initFailed");
        }
        log.info("Upload storage location: {}", this.fileStorageLocation);
    }

    /**
     * Validates and stores an uploaded file, returning the URL it will be served from.
     *
     * <p>Previously this method took whatever arrived and wrote it to disk under its original
     * extension. Since {@code /uploads/**} is served publicly as a static resource, that meant
     * any authenticated instructor could place a file of any type, under any extension, into a
     * directory the web server hands out — with the extension chosen by the uploader. This now
     * refuses anything that is not demonstrably one of the permitted image formats.
     */
    public String storeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("error.file.empty");
        }

        String extension = validatedExtension(file);
        validateDeclaredMediaType(file);
        // Must come last: it reads the bytes, and there is no reason to do that for a file the
        // cheap checks have already rejected.
        validateIsRealImage(file);

        try {
            // The stored name is a fresh UUID, never anything derived from the client's
            // filename. That removes path traversal ("../../etc/passwd"), null bytes, control
            // characters and collisions in one stroke, rather than trying to sanitise them.
            String newFileName = UUID.randomUUID() + "." + extension;
            Path targetLocation = this.fileStorageLocation.resolve(newFileName).normalize();

            // Defence in depth. A UUID plus a validated extension cannot escape the directory,
            // but this asserts it rather than assuming it.
            if (!targetLocation.startsWith(this.fileStorageLocation)) {
                throw new BusinessException("error.file.storeFailed");
            }

            try (InputStream in = file.getInputStream()) {
                Files.copy(in, targetLocation, StandardCopyOption.REPLACE_EXISTING);
            }

            return "/uploads/" + newFileName;
        } catch (IOException ex) {
            throw new BusinessException("error.file.storeFailed");
        }
    }

    /** The extension must be present and on the allow-list. */
    private String validatedExtension(MultipartFile file) {
        String originalFileName = StringUtils.cleanPath(
                file.getOriginalFilename() == null ? "" : file.getOriginalFilename());

        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0 || dot == originalFileName.length() - 1) {
            throw new BusinessException("error.file.extensionNotAllowed");
        }

        String extension = originalFileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!properties.allowedExtensions().contains(extension)) {
            throw new BusinessException("error.file.extensionNotAllowed");
        }
        return extension;
    }

    /**
     * The declared Content-Type must be on the allow-list. This is a cheap first gate and
     * nothing more: the header is supplied by the client and is trivially forged, which is
     * exactly why {@link #validateIsRealImage} exists.
     */
    private void validateDeclaredMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null
                || !properties.allowedMediaTypes().contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("error.file.typeNotAllowed");
        }
    }

    /**
     * Confirms the bytes really are a decodable image of a format ImageIO recognises.
     *
     * <p>This is the check that matters. Renaming {@code payload.svg} or a polyglot script to
     * {@code avatar.png} and setting {@code Content-Type: image/png} passes every check above;
     * it does not pass this one, because no {@link ImageReader} will claim the bytes.
     *
     * <p>Dimensions are read from the header rather than by decoding the pixels, so a
     * decompression bomb — a small file that expands to an enormous raster — is rejected on its
     * declared size instead of being materialised in memory first.
     */
    private void validateIsRealImage(MultipartFile file) {
        try (InputStream in = file.getInputStream();
             ImageInputStream imageStream = ImageIO.createImageInputStream(in)) {

            if (imageStream == null) {
                throw new BusinessException("error.file.notAnImage");
            }

            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageStream);
            if (!readers.hasNext()) {
                throw new BusinessException("error.file.notAnImage");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageStream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > properties.maxPixels()) {
                    throw new BusinessException("error.file.imageTooLarge");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            throw new BusinessException("error.file.notAnImage");
        }
    }

    public void deleteFile(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith("/uploads/")) {
            return;
        }
        try {
            String fileName = fileUrl.substring("/uploads/".length());
            Path targetLocation = this.fileStorageLocation.resolve(fileName).normalize();

            // Ensure the resolved path is genuinely inside the uploads directory. The previous
            // check compared getParent() with equals(), which throws a NullPointerException for
            // a single-segment path and silently accepts nothing else useful.
            if (!targetLocation.startsWith(this.fileStorageLocation)
                    || targetLocation.equals(this.fileStorageLocation)) {
                return;
            }

            Files.deleteIfExists(targetLocation);
        } catch (IOException | RuntimeException ex) {
            // A file that cannot be deleted must not fail the surrounding transaction — the
            // database change is what matters; an orphaned file is cosmetic.
            log.warn("Failed to delete upload {}", fileUrl, ex);
        }
    }
}
