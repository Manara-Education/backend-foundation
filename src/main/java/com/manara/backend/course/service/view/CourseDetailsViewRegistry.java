package com.manara.backend.course.service.view;

import com.manara.backend.common.exception.BusinessException;
import com.manara.backend.course.dto.CourseViewMode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class CourseDetailsViewRegistry {

    private final Map<CourseViewMode, CourseDetailsViewResolver> resolvers;

    public CourseDetailsViewRegistry(List<CourseDetailsViewResolver> resolvers) {
        this.resolvers = resolvers.stream()
                .collect(Collectors.toUnmodifiableMap(CourseDetailsViewResolver::mode, Function.identity()));
    }

    public CourseDetailsViewResolver get(CourseViewMode mode) {
        var resolver = resolvers.get(mode);
        if (resolver == null) {
            throw new BusinessException("error.course.unsupportedViewMode");
        }
        return resolver;
    }
}
