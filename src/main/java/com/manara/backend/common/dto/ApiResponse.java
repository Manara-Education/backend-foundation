package com.manara.backend.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.manara.backend.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private String status;
    private T data;
    private List<String> errors;

    /**
     * The machine-readable name of the condition behind an error, when it has one.
     *
     * <p>Omitted from the envelope entirely when absent, so this is additive: every response that
     * carried no code before still serialises byte-for-byte as it did. Clients that need to branch
     * on a specific refusal — a stale save, a retired plan — read this instead of matching on the
     * localized message.
     */
    private String code;

    public static <T> ApiResponse<T> success(T data) {
        return ApiResponse.<T>builder()
                .status("success")
                .data(data)
                .build();
    }

    public static <T> ApiResponse<T> error(List<String> errors) {
        return ApiResponse.<T>builder()
                .status("error")
                .errors(errors)
                .build();
    }

    public static <T> ApiResponse<T> error(String error) {
        return ApiResponse.<T>builder()
                .status("error")
                .errors(List.of(error))
                .build();
    }

    /** An error the client is expected to recognise, carrying both the prose and the code. */
    public static <T> ApiResponse<T> error(String error, ErrorCode code) {
        return ApiResponse.<T>builder()
                .status("error")
                .errors(List.of(error))
                .code(code == null ? null : code.name())
                .build();
    }
}
