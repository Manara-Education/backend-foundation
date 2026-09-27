package com.manara.backend.billing.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** The course a billing record belongs to. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record BillingCourseResponse(Long id, String title, String imageUrl, String instructorName) {
}
