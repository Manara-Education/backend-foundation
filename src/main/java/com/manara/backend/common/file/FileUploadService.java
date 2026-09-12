package com.manara.backend.common.file;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
public class FileUploadService {

    private final UploadProperties properties;
    private final UploadedImageReencoder reencoder;
    private final Path fileStorageLocation;

    public FileUploadService(UploadProperties properties, UploadedImageReencoder reencoder) {
        this.properties = properties;
        this.reencoder = reencoder;
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
     *
     * <p>What is stored is never the uploaded bytes. {@link UploadedImageReencoder} decodes the
     * image completely and writes a new file from its pixels, and the stored name's extension is
     * that of the format it wrote. The client's filename and Content-Type are only ever compared
     * against allow-lists, as a cheap gate before any of that work is done.
     *
     * <p>It also refuses anyone who is not an instructor. {@link UploadSecurityConfig} already
     * rejects those callers a filter earlier, which is what keeps a denied request from being
     * parsed at all; the check is repeated here because that one is a statement about a URL, and
     * this is the method that writes to the disk. Any future caller reaching the filesystem through
     * this service is covered without having to remember a matcher.
     *
     * <p>The uploader is taken as an argument rather than read from {@code SecurityContextHolder}
     * so that identity arrives explicitly, at the same boundary that will need to record who owns
     * the stored file.
     */
    public String storeFile(MultipartFile file, User uploader) {
        if (uploader == null || uploader.getRole() != Role.INSTRUCTOR) {
            throw new BusinessException("error.file.onlyInstructor");
        }

        if (file == null || file.isEmpty()) {
            throw new BusinessException("error.file.empty");
        }

        // The multipart limit refuses a larger request first. This is the method that reads the
        // bytes into memory, so it states its own bound rather than trusting configuration elsewhere.
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            throw new BusinessException("error.file.tooLarge");
        }

        validateExtension(file);
        validateDeclaredMediaType(file);

        Path temporary = null;
        boolean placed = false;
        try {
            byte[] source = file.getBytes();

            // Written beside its final location and moved into place in one step, so the served
            // directory never holds a half-written file under a name this store handed out.
            // createFile rather than createTempFile: the latter makes the file owner-only and the
            // mode survives the move; this takes the default permissions Files.copy used to.
            temporary = Files.createFile(
                    this.fileStorageLocation.resolve(".upload-" + UUID.randomUUID() + ".tmp"));
            UploadedImageReencoder.StoredFormat format = reencoder.reencode(source, temporary);

            // The stored name is a fresh UUID, never anything derived from the client's
            // filename. That removes path traversal ("../../etc/passwd"), null bytes, control
            // characters and collisions in one stroke, rather than trying to sanitise them.
            String newFileName = UUID.randomUUID() + "." + format.extension();
            Path targetLocation = this.fileStorageLocation.resolve(newFileName).normalize();

            // Defence in depth. A UUID plus an extension this service chose cannot escape the
            // directory, but this asserts it rather than assuming it.
            if (!targetLocation.startsWith(this.fileStorageLocation)) {
                throw new BusinessException("error.file.storeFailed");
            }

            Files.move(temporary, targetLocation, StandardCopyOption.ATOMIC_MOVE);
            placed = true;
            return "/uploads/" + newFileName;
        } catch (IOException ex) {
            log.warn("Failed to store an upload", ex);
            throw new BusinessException("error.file.storeFailed");
        } finally {
            // Every refusal after the temporary file exists — an image that does not decode
            // included — lands here, so none of them leaves a file in the served directory.
            if (!placed && temporary != null) {
                deleteTemporary(temporary);
            }
        }
    }

    /** The extension must be present and on the allow-list. */
    private void validateExtension(MultipartFile file) {
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
    }

    /**
     * The declared Content-Type must be on the allow-list. This is a cheap first gate and
     * nothing more: the header is supplied by the client and is trivially forged, which is
     * exactly why {@link UploadedImageReencoder} decodes the bytes themselves.
     */
    private void validateDeclaredMediaType(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null
                || !properties.allowedMediaTypes().contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new BusinessException("error.file.typeNotAllowed");
        }
    }

    private void deleteTemporary(Path temporary) {
        try {
            Files.deleteIfExists(temporary);
        } catch (IOException ex) {
            log.warn("Failed to delete temporary upload {}", temporary, ex);
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
