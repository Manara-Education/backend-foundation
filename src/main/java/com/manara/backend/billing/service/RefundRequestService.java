package com.manara.backend.billing.service;

import com.manara.backend.billing.dto.RefundRequestCreateRequest;
import com.manara.backend.billing.dto.RefundRequestResponse;
import com.manara.backend.billing.mapper.BillingMapper;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.RefundEligibility;
import com.manara.backend.billing.model.RefundRequest;
import com.manara.backend.billing.model.RefundRequestStatus;
import com.manara.backend.billing.repository.RefundRequestRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ConflictException;
import com.manara.backend.common.exception.ErrorCode;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Refund requests from the student's side: whether one can be made, making it, and reading it back.
 *
 * <p>A request is only a request. It changes neither the transaction's status, the paid totals nor
 * course access, and nothing here moves money. Who qualifies is {@link RefundPolicy}'s decision.
 * Review decisions are not implemented:
 * there is no staff surface to make them from, which is why accepting requests is off by default
 * ({@link RefundRequestProperties}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RefundRequestService {

    private final StudentBillingService billingService;
    private final RefundRequestRepository refundRequestRepository;
    private final RefundPolicy refundPolicy;
    private final BillingMapper billingMapper;
    private final Clock clock;

    public List<RefundRequestResponse> requests(User user, String transactionReference) {
        PaymentTransaction t = billingService.ownedTransaction(user, transactionReference);
        return refundRequestRepository.findByTransactionIdOrderByCreatedAtDescIdDesc(t.getId()).stream()
                .map(billingMapper::toRefundRequest)
                .toList();
    }

    @Transactional
    public RefundRequestResponse submit(User user, String transactionReference, RefundRequestCreateRequest request) {
        PaymentTransaction t = billingService.ownedTransaction(user, transactionReference);
        RefundEligibility eligibility = refundPolicy.eligibility(t);
        switch (eligibility) {
            case ELIGIBLE -> { }
            case UNAVAILABLE -> throw new BusinessException(ErrorCode.REFUND_REQUESTS_UNAVAILABLE, "error.refund.unavailable");
            case REQUEST_OPEN -> throw new ConflictException(ErrorCode.REFUND_REQUEST_OPEN, "error.refund.requestOpen");
            default -> throw new BusinessException(ErrorCode.REFUND_NOT_ELIGIBLE, "error.refund.notEligible." + eligibility.name());
        }
        RefundRequest saved;
        try {
            saved = refundRequestRepository.saveAndFlush(RefundRequest.builder()
                    .reference(UUID.randomUUID())
                    .transaction(t)
                    .student(t.getStudent())
                    .reason(request.reason())
                    .note(blankToNull(request.note()))
                    .amount(RefundPolicy.refundable(t))
                    .currency(t.getCurrency())
                    .status(RefundRequestStatus.SUBMITTED)
                    .createdAt(LocalDateTime.now(clock))
                    .build());
        } catch (DataIntegrityViolationException concurrent) {
            // Another submission for this transaction committed first; the partial unique index holds.
            throw new ConflictException(ErrorCode.REFUND_REQUEST_OPEN, "error.refund.requestOpen");
        }
        return billingMapper.toRefundRequest(saved);
    }

    private static String blankToNull(String note) {
        return note == null || note.isBlank() ? null : note.strip();
    }
}
