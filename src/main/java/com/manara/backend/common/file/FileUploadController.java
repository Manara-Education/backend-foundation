package com.manara.backend.common.file;

import com.manara.backend.common.dto.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/uploads")
@RequiredArgsConstructor
public class FileUploadController {

    private final FileUploadService fileUploadService;

    @PostMapping
    public ApiResponse<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) {
        String fileUrl = fileUploadService.storeFile(file);
        return ApiResponse.success(Map.of("url", fileUrl));
    }
}
