package com.manara.backend.billing.repository;

import com.manara.backend.billing.model.RefundRequest;
import com.manara.backend.billing.model.RefundRequestStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RefundRequestRepository extends JpaRepository<RefundRequest, Long> {

    List<RefundRequest> findByTransactionIdOrderByCreatedAtDescIdDesc(Long transactionId);

    boolean existsByTransactionIdAndStatusIn(Long transactionId, Collection<RefundRequestStatus> statuses);
}
