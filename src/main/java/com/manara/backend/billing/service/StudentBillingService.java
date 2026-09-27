package com.manara.backend.billing.service;

import com.manara.backend.billing.dto.CurrencyTotalResponse;
import com.manara.backend.billing.dto.ReceiptResponse;
import com.manara.backend.billing.dto.SubscriptionPageResponse;
import com.manara.backend.billing.dto.TransactionDetailResponse;
import com.manara.backend.billing.dto.TransactionPageResponse;
import com.manara.backend.billing.mapper.BillingMapper;
import com.manara.backend.billing.model.BillingReceipt;
import com.manara.backend.billing.model.PaymentTransaction;
import com.manara.backend.billing.model.TransactionProvenance;
import com.manara.backend.billing.model.TransactionStatus;
import com.manara.backend.billing.repository.BillingReceiptRepository;
import com.manara.backend.billing.repository.PaymentTransactionRepository;
import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.common.exception.ResourceNotFoundException;
import com.manara.backend.course.model.CourseEntitlement;
import com.manara.backend.course.model.CourseSubscription;
import com.manara.backend.course.repository.CourseEntitlementRepository;
import com.manara.backend.course.repository.CourseSubscriptionRepository;
import com.manara.backend.profile.model.Student;
import com.manara.backend.profile.repository.StudentRepository;
import com.manara.backend.user.model.Role;
import com.manara.backend.user.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A student's own billing records: subscriptions, transactions and receipts.
 *
 * <p>Everything is scoped to the caller's student row inside the queries. Another learner's id,
 * reference or receipt number is answered exactly like one that does not exist.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudentBillingService {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;
    private static final int MAX_QUERY_LENGTH = 100;
    /** Date filters are calendar days in server time, `from` and `to` both inclusive. */
    private static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    private static final LocalDate LATEST = LocalDate.of(2100, 1, 1);

    private final StudentRepository studentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final BillingReceiptRepository receiptRepository;
    private final CourseSubscriptionRepository subscriptionRepository;
    private final CourseEntitlementRepository entitlementRepository;
    private final BillingMapper billingMapper;
    private final ReceiptPdfRenderer receiptPdfRenderer;
    private final Clock clock;

    public TransactionPageResponse transactions(User user, String q, String status, LocalDate from, LocalDate to,
                                                int page, int size) {
        Student student = requireStudent(user);
        requirePaging(page, size);
        Collection<TransactionStatus> statuses = statuses(status);
        LocalDateTime start = (from == null ? EARLIEST : clampDate(from, "from")).atStartOfDay();
        LocalDateTime end = (to == null ? LATEST : clampDate(to, "to")).plusDays(1).atStartOfDay();
        if (!start.isBefore(end)) {
            throw new BusinessException("error.request.parameterInvalid", "to");
        }
        String like = like(q);

        Page<PaymentTransaction> found = transactionRepository.search(
                student.getId(), statuses, start, end, like, PageRequest.of(page, size));
        Map<Long, BillingReceipt> receipts = receiptsOf(found.getContent());
        Set<Long> accessible = accessibleCourses(student, found.getContent().stream().map(t -> t.getCourse().getId()).toList());

        List<CurrencyTotalResponse> totals = transactionRepository
                .confirmedTotals(student.getId(), statuses, start, end, like).stream()
                .map(row -> new CurrencyTotalResponse((String) row[0], (BigDecimal) row[1], ((Number) row[2]).longValue()))
                .toList();
        Map<String, Long> provenance = new LinkedHashMap<>();
        for (TransactionProvenance value : TransactionProvenance.values()) provenance.put(value.name(), 0L);
        transactionRepository.provenanceCounts(student.getId(), statuses, start, end, like)
                .forEach(row -> provenance.put(((TransactionProvenance) row[0]).name(), ((Number) row[1]).longValue()));

        return new TransactionPageResponse(
                found.getContent().stream()
                        .map(t -> billingMapper.toSummary(t, receipts.get(t.getId()), accessible.contains(t.getCourse().getId())))
                        .toList(),
                page, size, found.getTotalElements(), found.getTotalPages(), totals, provenance);
    }

    public TransactionDetailResponse transaction(User user, String reference) {
        Student student = requireStudent(user);
        PaymentTransaction t = transactionRepository.findOwned(parseReference(reference), student.getId())
                .orElseThrow(TransactionNotFound::new);
        BillingReceipt receipt = receiptRepository.findByTransactionId(t.getId()).orElse(null);
        boolean access = accessibleCourses(student, List.of(t.getCourse().getId())).contains(t.getCourse().getId());
        TransactionDetailResponse.SubscriptionTermResponse term = t.getSubscriptionId() == null ? null
                : subscriptionRepository.findById(t.getSubscriptionId())
                        .map(s -> new TransactionDetailResponse.SubscriptionTermResponse(s.getId(), s.getStartsAt(), s.getExpiresAt()))
                        .orElse(null);
        return billingMapper.toDetail(t, receipt, access, term);
    }

    public ReceiptResponse receipt(User user, String number) {
        return billingMapper.toReceiptResponse(ownedReceipt(user, number));
    }

    public byte[] receiptPdf(User user, String number) {
        return receiptPdfRenderer.render(billingMapper.toDocument(ownedReceipt(user, number)));
    }

    public SubscriptionPageResponse subscriptions(User user, int page, int size) {
        Student student = requireStudent(user);
        requirePaging(page, size);
        LocalDateTime now = LocalDateTime.now(clock);
        Page<CourseSubscription> found = subscriptionRepository.findPageForStudent(student.getId(), PageRequest.of(page, size));
        List<Long> ids = found.getContent().stream().map(CourseSubscription::getId).toList();
        Map<Long, PaymentTransaction> transactions = ids.isEmpty() ? Map.of()
                : transactionRepository.findBySubscriptionIdIn(ids).stream()
                        .collect(Collectors.toMap(PaymentTransaction::getSubscriptionId, Function.identity()));
        Set<Long> accessible = accessibleCourses(student, found.getContent().stream().map(s -> s.getCourse().getId()).toList());
        return new SubscriptionPageResponse(
                found.getContent().stream()
                        .map(s -> billingMapper.toSubscription(s, transactions.get(s.getId()),
                                accessible.contains(s.getCourse().getId()), now))
                        .toList(),
                page, size, found.getTotalElements(), found.getTotalPages(),
                subscriptionRepository.countActive(student.getId(), now));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private BillingReceipt ownedReceipt(User user, String number) {
        Student student = requireStudent(user);
        if (number == null || !number.matches("[A-Z]{3,4}-\\d{4}-\\d{6,}")) {
            throw new ResourceNotFoundException("error.billing.receiptNotFound");
        }
        return receiptRepository.findOwned(number, student.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.billing.receiptNotFound"));
    }

    private Set<Long> accessibleCourses(Student student, Collection<Long> courseIds) {
        if (courseIds.isEmpty()) return Set.of();
        LocalDateTime now = LocalDateTime.now(clock);
        return entitlementRepository.findByStudentIdAndCourseIdIn(student.getId(), Set.copyOf(courseIds)).stream()
                .filter(entitlement -> entitlement.isActiveAt(now))
                .map(entitlement -> entitlement.getCourse().getId())
                .collect(Collectors.toSet());
    }

    private Map<Long, BillingReceipt> receiptsOf(List<PaymentTransaction> transactions) {
        if (transactions.isEmpty()) return Map.of();
        return receiptRepository.findByTransactionIdIn(transactions.stream().map(PaymentTransaction::getId).toList())
                .stream().collect(Collectors.toMap(r -> r.getTransaction().getId(), Function.identity()));
    }

    private static Collection<TransactionStatus> statuses(String status) {
        if (status == null || status.isBlank()) return EnumSet.allOf(TransactionStatus.class);
        try {
            return Arrays.stream(status.split(","))
                    .map(value -> TransactionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT)))
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(TransactionStatus.class)));
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException("error.request.parameterInvalid", "status");
        }
    }

    private static String like(String q) {
        if (q == null || q.isBlank()) return "%";
        String trimmed = q.trim();
        if (trimmed.length() > MAX_QUERY_LENGTH) throw new BusinessException("error.request.parameterInvalid", "q");
        return "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
    }

    private static LocalDate clampDate(LocalDate date, String name) {
        if (date.isBefore(EARLIEST) || date.isAfter(LATEST)) {
            throw new BusinessException("error.request.parameterInvalid", name);
        }
        return date;
    }

    private static void requirePaging(int page, int size) {
        if (page < 0 || (long) page * size > Integer.MAX_VALUE) throw new BusinessException("error.request.parameterInvalid", "page");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new BusinessException("error.request.parameterInvalid", "size");
    }

    private static UUID parseReference(String reference) {
        try {
            return UUID.fromString(reference);
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new TransactionNotFound();
        }
    }

    private Student requireStudent(User user) {
        if (user == null || user.getRole() != Role.STUDENT) {
            throw new BusinessException("error.course.onlyStudent");
        }
        return studentRepository.findByUserId(user.getId())
                .orElseThrow(() -> new ResourceNotFoundException("error.profile.studentNotFound", user.getId().toString()));
    }

    private static final class TransactionNotFound extends ResourceNotFoundException {
        TransactionNotFound() {
            super("error.billing.transactionNotFound");
        }
    }
}
