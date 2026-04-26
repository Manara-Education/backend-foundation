package com.manara.backend.course.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Embeddable
public class SharedData {

    @Column(nullable = false)
    private String title;

    private String subtitle;

    private String image;

    @Column(columnDefinition = "TEXT")
    private String description;

    // Estimated total duration in minutes
    private Integer duration;
}
