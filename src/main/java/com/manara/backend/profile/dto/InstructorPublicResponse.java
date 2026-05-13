package com.manara.backend.profile.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class InstructorPublicResponse {
    private Long id;
    private String fullName;
    private String bio;
    private String specialization;
}
