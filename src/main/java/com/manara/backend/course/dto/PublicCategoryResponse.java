package com.manara.backend.course.dto;

/** A course's category on public pages. {@code color} is a design token, never a raw colour. */
public record PublicCategoryResponse(Long id, String name, String color) {
}
