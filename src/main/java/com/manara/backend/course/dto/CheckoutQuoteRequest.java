package com.manara.backend.course.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutQuoteRequest {

    /** Required for a subscription course; ignored otherwise. */
    private Long planId;
}
