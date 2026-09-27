package com.manara.backend.billing.controller;

import com.manara.backend.billing.dto.BillingCapabilitiesResponse;
import com.manara.backend.billing.dto.ReceiptResponse;
import com.manara.backend.billing.dto.RefundRequestCreateRequest;
import com.manara.backend.billing.dto.RefundRequestResponse;
import com.manara.backend.billing.dto.SubscriptionPageResponse;
import com.manara.backend.billing.dto.TransactionDetailResponse;
import com.manara.backend.billing.dto.TransactionPageResponse;
import com.manara.backend.billing.service.BillingCapabilities;
import com.manara.backend.billing.service.RefundRequestService;
import com.manara.backend.billing.service.StudentBillingService;
import com.manara.backend.common.dto.ApiResponse;
import com.manara.backend.user.model.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** The signed-in student's billing records. Owner-scoped; see {@link StudentBillingService}. */
@RestController
@RequestMapping("/api/v1/student")
@RequiredArgsConstructor
public class StudentBillingController {

    private final StudentBillingService billingService;
    private final BillingCapabilities billingCapabilities;
    private final RefundRequestService refundRequestService;

    /** What this deployment supports; see {@link BillingCapabilities}. */
    @GetMapping("/billing/capabilities")
    public ApiResponse<BillingCapabilitiesResponse> capabilities() {
        return ApiResponse.success(billingCapabilities.current());
    }

    @GetMapping("/subscriptions")
    public ApiResponse<SubscriptionPageResponse> subscriptions(@AuthenticationPrincipal User user,
                                                              @RequestParam(defaultValue = "0") int page,
                                                              @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(billingService.subscriptions(user, page, size));
    }

    /**
     * Transactions, newest first. {@code from}/{@code to} are inclusive calendar days (server time);
     * {@code status} is one status or a comma-separated list; {@code q} matches the item, the receipt
     * number and both references. The summary covers every row the filters match.
     */
    @GetMapping("/transactions")
    public ApiResponse<TransactionPageResponse> transactions(
            @AuthenticationPrincipal User user,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(billingService.transactions(user, q, status, from, to, page, size));
    }

    @GetMapping("/transactions/{reference}")
    public ApiResponse<TransactionDetailResponse> transaction(@AuthenticationPrincipal User user,
                                                              @PathVariable String reference) {
        return ApiResponse.success(billingService.transaction(user, reference));
    }

    /** The student's refund requests for one of their transactions, newest first. */
    @GetMapping("/transactions/{reference}/refund-requests")
    public ApiResponse<List<RefundRequestResponse>> refundRequests(@AuthenticationPrincipal User user,
                                                                   @PathVariable String reference) {
        return ApiResponse.success(refundRequestService.requests(user, reference));
    }

    /**
     * Asks for a refund of what remains on the transaction. Creates a request for review only: no money
     * moves and course access is unchanged. See {@link RefundRequestService}.
     */
    @PostMapping("/transactions/{reference}/refund-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RefundRequestResponse> requestRefund(@AuthenticationPrincipal User user,
                                                            @PathVariable String reference,
                                                            @Valid @RequestBody RefundRequestCreateRequest request) {
        return ApiResponse.success(refundRequestService.submit(user, reference, request));
    }

    @GetMapping("/receipts/{number}")
    public ApiResponse<ReceiptResponse> receipt(@AuthenticationPrincipal User user, @PathVariable String number) {
        return ApiResponse.success(billingService.receipt(user, number));
    }

    /** The receipt as a PDF, generated on the server from bundled fonts. */
    @GetMapping(value = "/receipts/{number}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> receiptPdf(@AuthenticationPrincipal User user, @PathVariable String number) {
        byte[] pdf = billingService.receiptPdf(user, number);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("manara-receipt-" + number + ".pdf").build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(pdf);
    }
}
