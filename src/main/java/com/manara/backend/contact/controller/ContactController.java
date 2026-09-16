package com.manara.backend.contact.controller;

import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.common.dto.MessageResponse;
import com.manara.backend.contact.dto.ContactRequest;
import com.manara.backend.contact.service.ContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public contact form. Anonymous, like registration — a visitor writing in has no account by
 * definition. Rate-limited in {@code RateLimitProperties} alongside the other endpoints that cause
 * an outbound email. The contract is {@code docs/api/CONTACT_API.md}.
 */
@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    public ApiResponse<MessageResponse> submit(@RequestBody @Valid ContactRequest request) {
        return ApiResponse.success(contactService.submit(request));
    }
}
