package com.manara.backend.billing.repository;

import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.TransactionStatus;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every read is bound to one student inside the query, before paging or aggregating: a list, a
 * count or a sum can never include another learner's row.
 */
public interface PaymentTransactionRepository extends JpaRepository<@NonNull PaymentTransaction, @NonNull Long> {

    String FILTER = """
            t.student.id = :studentId
              and t.status in :statuses
              and t.createdAt >= :from and t.createdAt < :to
              and (lower(t.lineDescription) like :q
                   or lower(coalesce(t.gatewayReference, '')) like :q
                   or lower(cast(t.reference as string)) like :q
                   or exists (select r.id from BillingReceipt r where r.transaction = t and lower(r.number) like :q))
            """;

    @Query(value = "select t from PaymentTransaction t join fetch t.course where " + FILTER
            + " order by t.createdAt desc, t.id desc",
            countQuery = "select count(t) from PaymentTransaction t where " + FILTER)
    Page<PaymentTransaction> search(@Param("studentId") Long studentId,
                                    @Param("statuses") Collection<TransactionStatus> statuses,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("q") String q,
                                    Pageable pageable);

    /** Confirmed money per currency, within the same filters: LIVE and PAID only. */
    @Query("select t.currency, sum(t.amount), count(t) from PaymentTransaction t where " + FILTER
            + " and t.provenance = com.manara.backend.billing.model.TransactionProvenance.LIVE"
            + " and t.status = com.manara.backend.billing.model.TransactionStatus.PAID"
            + " and t.amount is not null and t.currency is not null group by t.currency order by t.currency")
    List<Object[]> confirmedTotals(@Param("studentId") Long studentId,
                                   @Param("statuses") Collection<TransactionStatus> statuses,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to,
                                   @Param("q") String q);

    /** How many matching rows each provenance has, so the client can say what the total leaves out. */
    @Query("select t.provenance, count(t) from PaymentTransaction t where " + FILTER + " group by t.provenance")
    List<Object[]> provenanceCounts(@Param("studentId") Long studentId,
                                    @Param("statuses") Collection<TransactionStatus> statuses,
                                    @Param("from") LocalDateTime from,
                                    @Param("to") LocalDateTime to,
                                    @Param("q") String q);

    @Query("select t from PaymentTransaction t join fetch t.course where t.reference = :reference and t.student.id = :studentId")
    Optional<PaymentTransaction> findOwned(@Param("reference") UUID reference, @Param("studentId") Long studentId);

    List<PaymentTransaction> findBySubscriptionIdIn(Collection<Long> subscriptionIds);
}
