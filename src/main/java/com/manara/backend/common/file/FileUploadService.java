package com.manara.backend.common.file;

import com.manara.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class FileUploadService {

    private final Path fileStorageLocation;

    public FileUploadService() {
        this.fileStorageLocation = Paths.get("uploads").toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.fileStorageLocation);
        } catch (Exception ex) {
            throw new BusinessException("error.file.initFailed");
        }
    }

    public String storeFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException("error.file.empty");
        }

        String originalFileName = StringUtils.cleanPath(file.getOriginalFilename());
        String fileExtension = "";
        
        try {
            if (originalFileName.contains(".")) {
                fileExtension = originalFileName.substring(originalFileName.lastIndexOf("."));
            }
            
            String newFileName = UUID.randomUUID().toString() + fileExtension;
            Path targetLocation = this.fileStorageLocation.resolve(newFileName);
            Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

            return "/uploads/" + newFileName;
        } catch (IOException ex) {
            throw new BusinessException("error.file.storeFailed");
        }
    }

    public void deleteFile(String fileUrl) {
        if (fileUrl == null || !fileUrl.startsWith("/uploads/")) {
            return;
        }
        try {
            String fileName = fileUrl.substring("/uploads/".length());
            Path targetLocation = this.fileStorageLocation.resolve(fileName).normalize();
            
            // Basic security check to ensure it's inside the uploads directory
            if (!targetLocation.getParent().equals(this.fileStorageLocation)) {
                return;
            }

            Files.deleteIfExists(targetLocation);
        } catch (IOException ex) {
            // Log error, but don't fail the transaction if a file cannot be deleted
            // e.g. log.error("Failed to delete file: {}", fileUrl, ex);
        }
    }
}
