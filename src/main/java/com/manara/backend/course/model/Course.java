package com.manara.backend.course.model;

import com.manara.backend.profile.model.Instructor;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    private String subtitle;

    private String image;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Estimated total duration in minutes
    private Integer duration;

    @org.hibernate.annotations.Formula("(SELECT COUNT(l.id) FROM lessons l WHERE l.course_id = id)")
    private Integer lessonCount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "instructor_id", nullable = false)
    private Instructor instructor;

    private BigDecimal price;

    @Builder.Default
    @Column(name = "students_count", nullable = false)
    private Integer studentsCount = 0;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
