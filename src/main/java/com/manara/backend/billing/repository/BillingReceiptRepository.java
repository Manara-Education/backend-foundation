package com.manara.backend.billing.repository;

import com.manara.backend.billing.model.BillingReceipt;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface BillingReceiptRepository extends JpaRepository<@NonNull BillingReceipt, @NonNull Long> {

    List<BillingReceipt> findByTransactionIdIn(Collection<Long> transactionIds);

    Optional<BillingReceipt> findByTransactionId(Long transactionId);

    /** Found only through its owner: knowing a receipt number is not permission to read it. */
    @Query("select r from BillingReceipt r join fetch r.transaction t where r.number = :number and t.student.id = :studentId")
    Optional<BillingReceipt> findOwned(@Param("number") String number, @Param("studentId") Long studentId);

    @Query(value = "select nextval('payment_receipt_number_seq')", nativeQuery = true)
    long nextNumber();
}
