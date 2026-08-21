package com.manara.backend.course.service;

import com.manara.backend.course.model.CourseAccessType;
import com.manara.backend.course.model.CourseStatus;
import com.manara.backend.course.model.CourseStructure;

import java.math.BigDecimal;

/**
 * The course-level decisions a request resolves to, once defaults and the legacy price field have
 * been applied and the access rules validated.
 *
 * <p>Computed by {@link CourseValidator} so create and update reach the same conclusions from the
 * same payload, and so the mapper stays free of business rules.
 */
public record ResolvedCourseSettings(
        CourseStructure structure,
        CourseStatus status,
        CourseAccessType accessType,
        BigDecimal purchasePrice) {
}
