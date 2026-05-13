package com.manara.backend.course.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "lessons")
public class Lesson {

    /** Unique identifier for the lesson */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The title of the lesson */
    @Column(nullable = false)
    private String title;

    /** A brief summary of the lesson */
    @Column(columnDefinition = "TEXT")
    private String summary;

    /** Detailed description or content of the lesson */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** The extracted YouTube video ID used for embedding and fetching thumbnails */
    @Column(nullable = false)
    private String videoId;

    /** Duration of the video/lesson in seconds (or "HH:MM:SS" format depending on preference). Using Integer for minutes/seconds is common, let's use Integer for total seconds. */
    @Column
    private Integer duration;

    /** The order/position of this lesson within the course sequence */
    @Column(nullable = false)
    private Integer orderIndex;

    /** The course this lesson belongs to */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** Timestamp of when the lesson was created */
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Timestamp of when the lesson was last updated */
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
