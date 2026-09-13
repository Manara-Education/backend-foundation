package com.manara.backend.common.file;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

    // Declared, so a body of any other type is refused as 415 naming the one this endpoint reads,
    // rather than failing inside multipart resolution where it cannot be told from a server fault.
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file,
                                                       @AuthenticationPrincipal User uploader) {
        String fileUrl = fileUploadService.storeFile(file, uploader);
        return ApiResponse.success(Map.of("url", fileUrl));
    }
}
