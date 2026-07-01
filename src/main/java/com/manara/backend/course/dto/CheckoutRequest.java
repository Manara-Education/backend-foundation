package com.manara.backend.course.dto;

import jakarta.validation.constraints.Email;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CheckoutRequest {
    private String cardNumber;
    private String expiry;
    private String cvc;
    private String name;

    @Email
    private String email;
}
