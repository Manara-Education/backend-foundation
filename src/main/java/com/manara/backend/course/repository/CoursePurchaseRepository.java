package com.manara.backend.course.repository;

import com.manara.backend.course.model.CoursePurchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CoursePurchaseRepository extends JpaRepository<CoursePurchase, Long> {

    /** A learner's purchase history for one course, newest last. Read for support and audit. */
    List<CoursePurchase> findByCourseIdAndStudentIdOrderByPurchasedAtAsc(Long courseId, Long studentId);
}
