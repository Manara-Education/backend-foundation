package com.manara.backend.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {
    private String status; // "success" or "error"
    private T payload; // The actual data
    private List<String> errors; // Error details

    // Helper for success
    public static <T> ApiResponse<T> success(T payload) {
        return ApiResponse.<T>builder()
                .status("success")
                .payload(payload)
                .errors(null)
                .build();
    }

    // Helper for error
    public static <T> ApiResponse<T> error(List<String> errors) {
        return ApiResponse.<T>builder()
                .status("error")
                .payload(null)
                .errors(errors)
                .build();
    }
}
