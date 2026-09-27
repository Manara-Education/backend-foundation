package com.manara.backend.billing.mapper;

import com.manara.backend.billing.dto.BillingCourseResponse;
import com.manara.backend.billing.dto.ReceiptResponse;
import com.manara.backend.billing.dto.SubscriptionRecordResponse;
import com.manara.backend.billing.dto.TransactionDetailResponse;
import com.manara.backend.billing.dto.TransactionSummaryResponse;
import com.manara.backend.billing.model.BillingReceipt;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.TransactionProvenance;
import com.manara.backend.billing.model.TransactionPurpose;
import com.manara.backend.billing.model.TransactionStatus;
import com.manara.backend.billing.service.ReceiptDocument;
import com.manara.backend.course.model.Course;
import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.profile.model.Student;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Builds the ledger's rows. Pure: every derived value — reference, number, time — is passed in. */
@Component
public class BillingMapper {

    public PaymentTransaction toPaidTransaction(UUID reference, Student student, Course course, TransactionPurpose purpose,
                                                Long purchaseId, Long subscriptionId, Long planId, String lineDescription,
                                                PaymentReceipt gatewayReceipt, String currency, LocalDateTime now) {
        return PaymentTransaction.builder()
                .reference(reference)
                .student(student)
                .course(course)
                .purpose(purpose)
                .purchaseId(purchaseId)
                .subscriptionId(subscriptionId)
                .subscriptionPlanId(planId)
                .lineDescription(truncate(lineDescription))
                .amount(gatewayReceipt.amount())
                .currency(currency)
                .status(TransactionStatus.PAID)
                .provenance(gatewayReceipt.simulated() ? TransactionProvenance.SIMULATED : TransactionProvenance.LIVE)
                .gatewayReference(gatewayReceipt.reference())
                .createdAt(now)
                .paidAt(gatewayReceipt.paidAt() != null ? gatewayReceipt.paidAt() : now)
                .sourceKey("checkout:" + reference)
                .build();
    }

    public BillingReceipt toReceipt(PaymentTransaction transaction, String number, String customerName,
                                    String customerEmail, LocalDateTime issuedAt) {
        return BillingReceipt.builder()
                .number(number)
                .transaction(transaction)
                .issuedAt(issuedAt)
                .simulated(transaction.getProvenance() == TransactionProvenance.SIMULATED)
                .customerName(customerName)
                .customerEmail(customerEmail)
                .lineDescription(transaction.getLineDescription())
                .amount(transaction.getAmount())
                .currency(transaction.getCurrency())
                .gatewayReference(transaction.getGatewayReference())
                .build();
    }

    public ReceiptDocument toDocument(BillingReceipt receipt) {
        return new ReceiptDocument(receipt.getNumber(), receipt.getIssuedAt(), receipt.isSimulated(),
                receipt.getCustomerName(), receipt.getCustomerEmail(), receipt.getLineDescription(),
                receipt.getAmount(), receipt.getCurrency(), receipt.getTransaction().getReference().toString(),
                receipt.getGatewayReference());
    }

    private static String truncate(String value) {
        return value.length() <= 255 ? value : value.substring(0, 255);
    }

    // ── Responses ──────────────────────────────────────────────────────────────

    public BillingCourseResponse toCourse(Course course) {
        var instructor = course.getInstructor();
        String instructorName = instructor == null || instructor.getUser() == null ? null : instructor.getUser().getFullName();
        return new BillingCourseResponse(course.getId(), course.getTitle(), course.getImage(), instructorName);
    }

    public TransactionSummaryResponse toSummary(PaymentTransaction t, BillingReceipt receipt, boolean accessActive) {
        return new TransactionSummaryResponse(
                t.getReference().toString(), t.getPurpose().name(),
                new BillingCourseResponse(t.getCourse().getId(), t.getCourse().getTitle(), t.getCourse().getImage(), null),
                t.getLineDescription(), t.getAmount(), t.getCurrency(), t.getStatus().name(), t.getProvenance().name(),
                t.getCreatedAt(), t.getPaidAt(), receipt == null ? null : receipt.getNumber(),
                accessActive ? "ACTIVE" : "NONE");
    }

    public TransactionDetailResponse toDetail(PaymentTransaction t, BillingReceipt receipt, boolean accessActive,
                                              TransactionDetailResponse.SubscriptionTermResponse term) {
        BigDecimal amount = t.getAmount();
        return new TransactionDetailResponse(
                toSummary(t, receipt, accessActive),
                List.of(new TransactionDetailResponse.LineResponse(t.getLineDescription(), amount)),
                amount,
                amount == null ? null : BigDecimal.ZERO.setScale(2),
                amount,
                t.getRefundedAmount(),
                t.getGatewayReference(),
                term);
    }

    public ReceiptResponse toReceiptResponse(BillingReceipt r) {
        return new ReceiptResponse(r.getNumber(), r.getIssuedAt(), r.isSimulated(), false, r.getCustomerName(),
                r.getCustomerEmail(), r.getLineDescription(), r.getAmount(), r.getCurrency(),
                r.getTransaction().getReference().toString(), r.getGatewayReference());
    }

    public SubscriptionRecordResponse toSubscription(CourseSubscription s, PaymentTransaction transaction,
                                                     boolean accessActive, LocalDateTime now) {
        boolean running = s.isActiveAt(now);
        return new SubscriptionRecordResponse(
                s.getId(), toCourse(s.getCourse()),
                new SubscriptionRecordResponse.PlanResponse(s.getPlan().getId(), s.getPlan().getName(),
                        s.getPlan().getDuration(), s.getPlan().getUnit().name()),
                s.getPricePaid(),
                transaction == null ? null : transaction.getCurrency(),
                s.getStartsAt(), s.getExpiresAt(), s.getStatus().name(),
                running ? "FIXED_ACCESS" : "EXPIRED",
                "FIXED",
                accessActive ? "ACTIVE" : "NONE",
                transaction == null ? null : transaction.getReference().toString(),
                transaction == null ? null : transaction.getProvenance().name());
    }
}
