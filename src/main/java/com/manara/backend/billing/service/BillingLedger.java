package com.manara.backend.billing.service;

import com.manara.backend.billing.mapper.BillingMapper;
import com.manara.backend.billing.model.BillingReceipt;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.TransactionPurpose;
import com.manara.backend.billing.repository.BillingReceiptRepository;
import com.manara.backend.billing.repository.PaymentTransactionRepository;
import com.manara.backend.course.model.Course;
import com.manara.backend.payment.model.PaymentReceipt;
import com.manara.backend.profile.model.Student;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Writes the ledger for a charge checkout has just accepted.
 *
 * <p>Called inside checkout's own transaction ({@link Propagation#MANDATORY}), next to the purchase
 * or subscription row the charge paid for: the charge, its record and the access it grants commit
 * together or not at all. The unique links to that row mean one charge can never be recorded twice.
 * A receipt is issued for every paid transaction recorded here; a simulated one is numbered DEMO-.
 */
@Service
@RequiredArgsConstructor
public class BillingLedger {

    private final PaymentTransactionRepository transactionRepository;
    private final BillingReceiptRepository receiptRepository;
    private final BillingMapper billingMapper;
    private final Clock clock;

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry recordPurchase(Student student, Course course, Long purchaseId, PaymentReceipt charge, String currency) {
        return record(student, course, TransactionPurpose.PURCHASE, purchaseId, null, null, course.getTitle(), charge, currency);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public LedgerEntry recordSubscription(Student student, Course course, Long subscriptionId, Long planId,
                                          String lineDescription, PaymentReceipt charge, String currency) {
        return record(student, course, TransactionPurpose.SUBSCRIPTION, null, subscriptionId, planId, lineDescription, charge, currency);
    }

    private LedgerEntry record(Student student, Course course, TransactionPurpose purpose, Long purchaseId,
                               Long subscriptionId, Long planId, String line, PaymentReceipt charge, String currency) {
        LocalDateTime now = LocalDateTime.now(clock);
        PaymentTransaction transaction = transactionRepository.save(billingMapper.toPaidTransaction(
                UUID.randomUUID(), student, course, purpose, purchaseId, subscriptionId, planId, line, charge, currency, now));
        String number = "%s-%d-%06d".formatted(charge.simulated() ? "DEMO" : "RCT", now.getYear(), receiptRepository.nextNumber());
        BillingReceipt receipt = receiptRepository.save(billingMapper.toReceipt(
                transaction, number, student.getUser().getFullName(), student.getUser().getEmail(), now));
        return new LedgerEntry(transaction, receipt);
    }
}
