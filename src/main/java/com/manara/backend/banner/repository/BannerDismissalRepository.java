package com.manara.backend.banner.repository;

import com.manara.backend.banner.model.BannerDismissal;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BannerDismissalRepository extends JpaRepository<BannerDismissal, Long> {

    @Query("select d.banner.id from BannerDismissal d where d.student.id = :studentId")
    List<Long> findDismissedBannerIds(@Param("studentId") Long studentId);

    boolean existsByBannerIdAndStudentId(Long bannerId, Long studentId);

    void deleteByBannerId(Long bannerId);
}
